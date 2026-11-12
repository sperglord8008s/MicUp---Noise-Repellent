#include "AudioEngine.h"
#include "PluginHost.h"
#include "ClapHost.h"
#include "Vst3Bridge.h"
#include "Lv2Host.h"

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>
#include <algorithm>
#include <numeric>

#define LOG_TAG "MicPlugin.Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── JNI monitor callback globals (file scope, outside micplugin namespace) ──
#include <vector>
#include <jni.h>

static JavaVM*   gJvm             = nullptr;
static jobject   gMonitorCallback = nullptr;
static jmethodID gInvokeMethod    = nullptr;

// Called from audio thread — zero-copy float pass, no conversion
static void dispatchToKotlin(const float* buf, int32_t numFrames) {
    if (!gJvm || !gMonitorCallback || !gInvokeMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    jint status = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        gJvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    if (!env) return;

    // Pass float[] directly — no short conversion, no quality loss, no clipping risk
    jfloatArray arr = env->NewFloatArray(numFrames);
    if (arr) {
        env->SetFloatArrayRegion(arr, 0, numFrames, buf);
        jobject jSize = env->CallStaticObjectMethod(
            env->FindClass("java/lang/Integer"),
            env->GetStaticMethodID(env->FindClass("java/lang/Integer"),
                "valueOf", "(I)Ljava/lang/Integer;"),
            (jint)numFrames
        );
        env->CallObjectMethod(gMonitorCallback, gInvokeMethod, arr, jSize);
        env->DeleteLocalRef(arr);
        if (jSize) env->DeleteLocalRef(jSize);
    }
    if (attached) gJvm->DetachCurrentThread();
}


namespace micplugin {

// ─── SpectrumAnalyzer ───────────────────────────────────────────────────────
SpectrumAnalyzer::SpectrumAnalyzer() {
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(
            2.0f * static_cast<float>(M_PI) * static_cast<float>(i) /
            static_cast<float>(kFftSize - 1));
    }
    for (int i = 0; i < kFftSize / 2; ++i) {
        const float angle = -2.0f * static_cast<float>(M_PI) *
            static_cast<float>(i) / static_cast<float>(kFftSize);
        twiddleReal_[i] = std::cos(angle);
        twiddleImag_[i] = std::sin(angle);
    }
    smoothed_.fill(-120.0f);
    for (auto& value : published_) {
        value.store(-120.0f, std::memory_order_relaxed);
    }
}

bool SpectrumAnalyzer::process(const float* samples, int32_t frameCount) {
    bool published = false;
    int32_t sourceOffset = 0;
    while (sourceOffset < frameCount) {
        const int copyCount = std::min(
            frameCount - sourceOffset, kFftSize - inputCount_);
        std::memcpy(input_.data() + inputCount_, samples + sourceOffset,
                    static_cast<size_t>(copyCount) * sizeof(float));
        inputCount_ += copyCount;
        sourceOffset += copyCount;
        if (inputCount_ == kFftSize) {
            transformAndPublish();
            inputCount_ = 0;
            published = true;
        }
    }
    return published;
}

void SpectrumAnalyzer::transformAndPublish() {
    for (int i = 0; i < kFftSize; ++i) {
        real_[i] = input_[i] * window_[i];
        imag_[i] = 0.0f;
    }

    // In-place radix-2 FFT. Twiddles are precomputed at construction time.
    for (int i = 1, j = 0; i < kFftSize; ++i) {
        int bit = kFftSize >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(real_[i], real_[j]);
            std::swap(imag_[i], imag_[j]);
        }
    }
    for (int length = 2; length <= kFftSize; length <<= 1) {
        const int half = length >> 1;
        const int twiddleStep = kFftSize / length;
        for (int base = 0; base < kFftSize; base += length) {
            for (int j = 0; j < half; ++j) {
                const int twiddle = j * twiddleStep;
                const float wr = twiddleReal_[twiddle];
                const float wi = twiddleImag_[twiddle];
                const int even = base + j;
                const int odd = even + half;
                const float tr = wr * real_[odd] - wi * imag_[odd];
                const float ti = wr * imag_[odd] + wi * real_[odd];
                const float er = real_[even];
                const float ei = imag_[even];
                real_[even] = er + tr;
                imag_[even] = ei + ti;
                real_[odd] = er - tr;
                imag_[odd] = ei - ti;
            }
        }
    }

    constexpr float kHannMagnitudeScale = 4.0f /
        static_cast<float>(kFftSize);
    for (int bin = 0; bin < kBinCount; ++bin) {
        const float magnitude = std::sqrt(
            real_[bin] * real_[bin] + imag_[bin] * imag_[bin]) *
            (bin == 0 ? kHannMagnitudeScale * 0.5f : kHannMagnitudeScale);
        const float db = std::clamp(
            20.0f * std::log10(std::max(magnitude, 1.0e-6f)),
            -120.0f, 6.0f);
        const float coefficient = db > smoothed_[bin] ? 0.62f : 0.18f;
        smoothed_[bin] += coefficient * (db - smoothed_[bin]);
        published_[bin].store(smoothed_[bin], std::memory_order_relaxed);
    }
    generation_.fetch_add(1, std::memory_order_release);
}

void SpectrumAnalyzer::copyPublished(float* destination) const {
    for (int i = 0; i < kBinCount; ++i) {
        destination[i] = published_[i].load(std::memory_order_relaxed);
    }
}

// ─── BiquadFilter coefficient computation ────────────────────────────────────
void BiquadFilter::setPeak(float fc, float gainDb, float Q, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float alpha = sinW / (2.0f * Q);
    float a0inv = 1.0f / (1.0f + alpha / A);
    b0 = (1.0f + alpha * A) * a0inv;
    b1 = (-2.0f * cosW)     * a0inv;
    b2 = (1.0f - alpha * A) * a0inv;
    a1 = b1;
    a2 = (1.0f - alpha / A) * a0inv;
}

void BiquadFilter::setLowShelf(float fc, float gainDb, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float beta = std::sqrt(A) * sinW;
    float a0inv = 1.0f / ((A+1) + (A-1)*cosW + beta);
    b0 = A * ((A+1) - (A-1)*cosW + beta)          * a0inv;
    b1 = 2.0f * A * ((A-1) - (A+1)*cosW)          * a0inv;
    b2 = A * ((A+1) - (A-1)*cosW - beta)           * a0inv;
    a1 = -2.0f * ((A-1) + (A+1)*cosW)              * a0inv;
    a2 = ((A+1) + (A-1)*cosW - beta)               * a0inv;
}

void BiquadFilter::setHighShelf(float fc, float gainDb, float sr) {
    float A  = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * M_PI * fc / sr;
    float cosW = std::cos(w0);
    float sinW = std::sin(w0);
    float beta = std::sqrt(A) * sinW;
    float a0inv = 1.0f / ((A+1) - (A-1)*cosW + beta);
    b0 = A * ((A+1) + (A-1)*cosW + beta)          * a0inv;
    b1 = -2.0f * A * ((A-1) + (A+1)*cosW)         * a0inv;
    b2 = A * ((A+1) + (A-1)*cosW - beta)           * a0inv;
    a1 = 2.0f * ((A-1) - (A+1)*cosW)              * a0inv;
    a2 = ((A+1) - (A-1)*cosW - beta)              * a0inv;
}

// ─── AudioEngine ─────────────────────────────────────────────────────────────
AudioEngine::AudioEngine() {
    // Init Hann window for pitch shifter
    for (int i = 0; i < kGrainSize; i++) {
        pitchWindow_[i] = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / kGrainSize));
    }
    std::fill(pitchIn_,  pitchIn_  + kGrainSize*2, 0.0f);
    std::fill(pitchOut_, pitchOut_ + kGrainSize*2, 0.0f);
    for (auto& value : noiseSpectrum_) {
        value.store(-120.0f, std::memory_order_relaxed);
    }
}

AudioEngine::~AudioEngine() {
    stop();
    if (noiseDenoiser_) {
        specbleach_denoiser_free(noiseDenoiser_);
        noiseDenoiser_ = nullptr;
    }
}

bool AudioEngine::start() {
    if (running_.load()) return true;
    ring_.reset();
    if (!openStreams()) return false;
    running_.store(true, std::memory_order_release);
    LOGI("AudioEngine started: %dHz, %d frames/burst", sampleRate_, framesPerBurst_);
    return true;
}

void AudioEngine::stop() {
    running_.store(false, std::memory_order_release);
    closeStreams();
}

bool AudioEngine::openStreams() {
    oboe::AudioStreamBuilder builder;

    // ─ Input stream (microphone capture) ─────────────────────────────────────
    // deviceId defaults to oboe::kUnspecified (system default mic) unless the user picked
    // a specific input (e.g. a USB sound card) in Settings — see setInputDeviceId().
    int32_t preferredInputId = inputDeviceId_.load(std::memory_order_relaxed);
    const bool communicationInput =
        communicationInput_.load(std::memory_order_relaxed);
    const auto preferredPreset = communicationInput
        ? oboe::InputPreset::VoiceCommunication
        : oboe::InputPreset::Unprocessed;
    auto result = builder
        .setDirection(oboe::Direction::Input)
        ->setDeviceId(preferredInputId)
        ->setInputPreset(preferredPreset)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setChannelCount(1)
        ->setSampleRate(48000)
        ->openStream(inputStream_);

    // Some devices do not expose Android's raw/unprocessed capture preset.
    // VoiceRecognition is the least-processed broadly supported fallback.
    if (result != oboe::Result::OK && !communicationInput) {
        LOGE("Unprocessed input unavailable: %s — retrying VoiceRecognition",
             oboe::convertToText(result));
        builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        result = builder.openStream(inputStream_);
    }

    // Bluetooth SCO/BLE and a number of USB HALs do not offer exclusive capture.
    // Retry the requested source in shared mode before falling back to a different mic.
    if (result != oboe::Result::OK) {
        LOGE("Exclusive input unavailable: %s — retrying selected route shared",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
               ->setInputPreset(communicationInput
                    ? oboe::InputPreset::VoiceCommunication
                    : oboe::InputPreset::VoiceRecognition);
        result = builder.openStream(inputStream_);
    }

    if (result != oboe::Result::OK && preferredInputId != oboe::kUnspecified) {
        // The chosen device may have been unplugged/unavailable — fall back to the
        // system default rather than leaving the engine unable to start at all.
        LOGE("Failed to open input stream on device %d: %s — retrying with default device",
             preferredInputId, oboe::convertToText(result));
        builder.setDeviceId(oboe::kUnspecified)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setInputPreset(communicationInput
                    ? oboe::InputPreset::VoiceCommunication
                    : oboe::InputPreset::VoiceRecognition);
        result = builder.openStream(inputStream_);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    sampleRate_    = inputStream_->getSampleRate();
    framesPerBurst_ = inputStream_->getFramesPerBurst();
    LOGI("Input stream opened: %dHz, %d frames/burst", sampleRate_, framesPerBurst_);

    // Allocate/configure the spectral processor before either stream starts.
    // Failure is non-fatal: MicUp continues with the rest of its DSP chain.
    if (!prepareNoiseRepellent()) {
        LOGE("Noise Repellent engine unavailable; continuing without denoising");
    }

    // ─ Silent callback-driver output stream ──────────────────────────────────
    builder.setDirection(oboe::Direction::Output)
           // Do not carry the selected USB input's device id into this silent
           // driver stream. Audible routing is handled by SoftwareLoopback.
           ->setDeviceId(oboe::kUnspecified)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           // This silent driver must coexist with AudioTrack and Bluetooth endpoints.
           // Exclusive mode is frequently unavailable on SCO/A2DP routes.
           ->setSharingMode(oboe::SharingMode::Shared)
           ->setFormat(oboe::AudioFormat::Float)
           ->setFormatConversionAllowed(true)
           ->setChannelConversionAllowed(true)
           ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
           ->setChannelCount(1)
           ->setSampleRate(sampleRate_)
           ->setFramesPerDataCallback(framesPerBurst_)
           ->setDataCallback(this)
           ->setErrorCallback(this)
           // This stream is deliberately zero-filled below and exists only to drive the
           // real-time callback. Advertising it as VoiceCommunication made Android treat it
           // as a second routing request, which could pull a separately routed A2DP monitor
           // back to the speaker/SCO path. Audible routing belongs exclusively to
           // SoftwareLoopback's verified AudioTrack.
           ->setUsage(oboe::Usage::Media)
           ->setContentType(oboe::ContentType::Speech);

    result = builder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        inputStream_->close();
        return false;
    }

    // Boost audio thread priority (SCHED_FIFO P96 via Oboe)
    outputStream_->setBufferSizeInFrames(framesPerBurst_ * 2);

    // Start both streams
    inputStream_->requestStart();
    outputStream_->requestStart();

    eqDirty_ = true;
    rebuildEQ();
    return true;
}

void AudioEngine::closeStreams() {
    if (outputStream_) { outputStream_->requestStop(); outputStream_->close(); outputStream_.reset(); }
    if (inputStream_)  { inputStream_->requestStop();  inputStream_->close();  inputStream_.reset();  }
}

bool AudioEngine::prepareNoiseRepellent() {
    const uint32_t rate = static_cast<uint32_t>(sampleRate_);
    if (noiseDenoiser_ && noiseDenoiserSampleRate_ == rate) {
        return true;
    }

    if (noiseDenoiser_) {
        specbleach_denoiser_free(noiseDenoiser_);
        noiseDenoiser_ = nullptr;
    }

    const int qualityMode = std::clamp(
        params_.noiseQualityMode.load(std::memory_order_relaxed), 0, 2);
    const uint32_t initFlags = qualityMode == 0
        ? SPECBLEACH_INIT_LOW_LATENCY
        : 0u;
    // Live: causal 512-sample temporal path. Balanced and Studio use the
    // full 2D engine at 20/40 ms respectively, trading latency and CPU for
    // better frequency resolution and fewer musical-noise artifacts.
    const float frameMs = qualityMode == 0
        ? 512.0f * 1000.0f / static_cast<float>(rate)
        : (qualityMode == 1 ? 20.0f : 40.0f);
    noiseDenoiser_ = specbleach_denoiser_initialize(
        rate, frameMs, initFlags);
    if (!noiseDenoiser_) {
        noiseDenoiserSampleRate_ = 0;
        params_.noiseEngineReady.store(false, std::memory_order_release);
        params_.noiseLatencyFrames.store(0, std::memory_order_relaxed);
        params_.noiseFrameSize.store(0, std::memory_order_relaxed);
        params_.noiseFftSize.store(0, std::memory_order_relaxed);
        params_.noiseHopSize.store(0, std::memory_order_relaxed);
        params_.noiseProfileSize.store(0, std::memory_order_relaxed);
        params_.noiseProfileReady.store(false, std::memory_order_relaxed);
        return false;
    }

    noiseDenoiserSampleRate_ = rate;
    const uint32_t profileSize =
        specbleach_denoiser_get_noise_profile_size(noiseDenoiser_);
    noiseReductionCurve_.assign(profileSize, 0.0f);

    // Prewarm both the curve-copy buffer and the selected adaptive estimator
    // before audio starts. Later UI changes are allocation-free in callback.
    const bool adaptiveRequested =
        params_.noiseAdaptive.load(std::memory_order_relaxed);
    if (!loadNoiseRepellentParams(true) ||
        (!adaptiveRequested && !loadNoiseRepellentParams(false))) {
        specbleach_denoiser_free(noiseDenoiser_);
        noiseDenoiser_ = nullptr;
        noiseDenoiserSampleRate_ = 0;
        params_.noiseLatencyFrames.store(0, std::memory_order_relaxed);
        params_.noiseProfileReady.store(false, std::memory_order_relaxed);
        return false;
    }
    params_.noiseLatencyFrames.store(
        specbleach_denoiser_get_latency(noiseDenoiser_),
        std::memory_order_relaxed);
    params_.noiseFrameSize.store(
        specbleach_denoiser_get_frame_size(noiseDenoiser_),
        std::memory_order_relaxed);
    params_.noiseFftSize.store(
        specbleach_denoiser_get_fft_size(noiseDenoiser_),
        std::memory_order_relaxed);
    params_.noiseHopSize.store(
        specbleach_denoiser_get_hop_size(noiseDenoiser_),
        std::memory_order_relaxed);
    params_.noiseProfileSize.store(profileSize, std::memory_order_relaxed);
    params_.noiseProfileReady.store(false, std::memory_order_relaxed);
    params_.noiseEngineReady.store(true, std::memory_order_release);
    params_.noiseParamsDirty.store(false, std::memory_order_relaxed);
    outputGainSmoothed_ = dBToLin(
        params_.noiseOutputGainDb.load(std::memory_order_relaxed));

    LOGI("Noise Repellent ready: %uHz, %u frames latency",
         rate, params_.noiseLatencyFrames.load(std::memory_order_relaxed));
    return true;
}

bool AudioEngine::loadNoiseRepellentParams(bool prewarmAdaptive) {
    if (!noiseDenoiser_) return false;

    SpecbleachDenoiserParameters noiseParams =
        specbleach_denoiser_get_default_parameters();
    noiseParams.learn_noise =
        params_.noiseLearning.load(std::memory_order_relaxed)
            ? SPECBLEACH_LEARN_ALL
            : SPECBLEACH_LEARN_OFF;
    noiseParams.residual_listen =
        params_.noiseResidualListen.load(std::memory_order_relaxed);

    const float reductionDb = std::clamp(
        params_.noiseReductionDb.load(std::memory_order_relaxed), 0.0f, 40.0f);
    noiseParams.reduction_gain = dBToLin(-reductionDb);
    const float tonalReductionDb = std::clamp(
        params_.noiseTonalReductionDb.load(std::memory_order_relaxed),
        0.0f, 40.0f);
    noiseParams.tonal_reduction_gain = dBToLin(-tonalReductionDb);
    noiseParams.smoothing_factor = std::clamp(
        params_.noiseSmoothing.load(std::memory_order_relaxed), 0.0f, 1.0f);
    noiseParams.smoothing_mode = static_cast<SpecbleachSmoothingMode>(std::clamp(
        params_.noiseSmoothingMode.load(std::memory_order_relaxed), 0, 3));
    noiseParams.dftt_strength = std::clamp(
        params_.noiseDfttStrength.load(std::memory_order_relaxed), 0.0f, 4.0f);
    noiseParams.masking_depth = std::clamp(
        params_.noiseMasking.load(std::memory_order_relaxed), 0.0f, 1.0f);
    noiseParams.whitening_factor = std::clamp(
        params_.noiseWhitening.load(std::memory_order_relaxed), 0.0f, 1.0f);
    noiseParams.suppression_strength = std::clamp(
        params_.noiseSuppressionStrength.load(std::memory_order_relaxed),
        0.0f, 1.0f);
    noiseParams.aggressiveness = std::clamp(
        params_.noiseAggressiveness.load(std::memory_order_relaxed), -1.0f, 1.0f);
    noiseParams.noise_profile_scale = std::pow(
        10.0f,
        std::clamp(params_.noiseProfileOffsetDb.load(std::memory_order_relaxed),
                   -12.0f, 12.0f) /
            10.0f);
    noiseParams.tonal_noise_profile_scale = std::pow(
        10.0f,
        std::clamp(
            params_.noiseTonalProfileOffsetDb.load(std::memory_order_relaxed),
            -12.0f, 12.0f) /
            10.0f);
    noiseParams.transient_protection_enable =
        params_.noiseTransientProtection.load(std::memory_order_relaxed);
    noiseParams.adaptive_noise = prewarmAdaptive ||
        params_.noiseAdaptive.load(std::memory_order_relaxed);
    noiseParams.noise_estimation_method =
        static_cast<SpecbleachNoiseEstimationMethod>(std::clamp(
            params_.noiseEstimatorMethod.load(std::memory_order_relaxed), 0, 2));

    buildNoiseReductionCurve();
    // Always keep the correctly-sized curve loaded. With the feature off the
    // curve is all zeroes, which is acoustically neutral and avoids a future
    // allocation when the user enables it.
    noiseParams.reduction_curve_enabled = true;
    noiseParams.reduction_curve_bias = noiseReductionCurve_.data();
    noiseParams.reduction_curve_size =
        static_cast<uint32_t>(noiseReductionCurve_.size());

    const bool loaded = specbleach_denoiser_load_parameters(
        noiseDenoiser_, &noiseParams, sizeof(noiseParams));
    params_.noiseEngineReady.store(loaded, std::memory_order_release);
    return loaded;
}

void AudioEngine::buildNoiseReductionCurve() {
    if (noiseReductionCurve_.empty()) return;
    if (!params_.noiseCurveEnabled.load(std::memory_order_relaxed)) {
        std::fill(noiseReductionCurve_.begin(), noiseReductionCurve_.end(), 0.0f);
        return;
    }

    static constexpr float kBandFrequencies[10] = {
        31.0f, 63.0f, 125.0f, 250.0f, 500.0f,
        1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f,
    };
    float bandValues[10];
    for (int i = 0; i < 10; ++i) {
        bandValues[i] = std::clamp(
            params_.noiseCurveBiasDb[i].load(std::memory_order_relaxed),
            -15.0f, 15.0f);
    }

    const float binHz = static_cast<float>(sampleRate_) /
        static_cast<float>(std::max<uint32_t>(
            params_.noiseFftSize.load(std::memory_order_relaxed),
            static_cast<uint32_t>((noiseReductionCurve_.size() - 1) * 2)));
    for (size_t bin = 0; bin < noiseReductionCurve_.size(); ++bin) {
        const float frequency = static_cast<float>(bin) * binHz;
        int upper = 1;
        while (upper < 10 && frequency > kBandFrequencies[upper]) ++upper;
        if (frequency <= kBandFrequencies[0]) {
            noiseReductionCurve_[bin] = bandValues[0];
        } else if (upper >= 10) {
            noiseReductionCurve_[bin] = bandValues[9];
        } else {
            const float lowerHz = kBandFrequencies[upper - 1];
            const float fraction = (frequency - lowerHz) /
                (kBandFrequencies[upper] - lowerHz);
            noiseReductionCurve_[bin] = bandValues[upper - 1] + fraction *
                (bandValues[upper] - bandValues[upper - 1]);
        }
    }
}

void AudioEngine::reconfigureNoiseRepellent() {
    const bool wasRunning = running_.exchange(false, std::memory_order_acq_rel);
    if (wasRunning) closeStreams();

    if (noiseDenoiser_) {
        specbleach_denoiser_free(noiseDenoiser_);
        noiseDenoiser_ = nullptr;
    }
    noiseDenoiserSampleRate_ = 0;
    noiseReductionCurve_.clear();
    params_.noiseEngineReady.store(false, std::memory_order_release);
    params_.noiseProfileReady.store(false, std::memory_order_release);
    params_.noiseLatencyFrames.store(0, std::memory_order_relaxed);
    params_.noiseFrameSize.store(0, std::memory_order_relaxed);
    params_.noiseFftSize.store(0, std::memory_order_relaxed);
    params_.noiseHopSize.store(0, std::memory_order_relaxed);
    params_.noiseProfileSize.store(0, std::memory_order_relaxed);
    params_.noiseProfileBlocks.store(0, std::memory_order_relaxed);
    params_.noiseLearning.store(false, std::memory_order_relaxed);
    params_.noiseParamsDirty.store(true, std::memory_order_release);

    if (wasRunning) {
        ring_.reset();
        if (openStreams()) {
            running_.store(true, std::memory_order_release);
        } else {
            LOGE("Failed to restart audio after reconfiguring Noise Repellent");
        }
    }
}

void AudioEngine::setNoiseQualityMode(int mode) {
    const int clamped = std::clamp(mode, 0, 2);
    if (params_.noiseQualityMode.exchange(clamped, std::memory_order_acq_rel) !=
        clamped) {
        reconfigureNoiseRepellent();
    }
}

void AudioEngine::setNoiseEstimatorMethod(int method) {
    const int clamped = std::clamp(method, 0, 2);
    if (params_.noiseEstimatorMethod.exchange(
            clamped, std::memory_order_acq_rel) != clamped) {
        reconfigureNoiseRepellent();
    }
}

void AudioEngine::copySpectrumSnapshot(float* destination) const {
    destination[0] = static_cast<float>(sampleRate_);
    destination[1] = static_cast<float>(SpectrumAnalyzer::kFftSize);
    destination[2] = static_cast<float>(inputSpectrum_.generation());
    inputSpectrum_.copyPublished(destination + 3);
    outputSpectrum_.copyPublished(
        destination + 3 + SpectrumAnalyzer::kBinCount);
    float* noiseDestination =
        destination + 3 + SpectrumAnalyzer::kBinCount * 2;
    for (int i = 0; i < SpectrumAnalyzer::kBinCount; ++i) {
        noiseDestination[i] =
            noiseSpectrum_[i].load(std::memory_order_relaxed);
    }
}

void AudioEngine::publishNoiseDiagnostics() {
    if (!noiseDenoiser_) return;

    const uint32_t profileSize =
        specbleach_denoiser_get_noise_profile_size(noiseDenoiser_);
    const uint32_t denoiserFftSize =
        specbleach_denoiser_get_fft_size(noiseDenoiser_);
    const float* profile =
        specbleach_denoiser_get_active_noise_profile(noiseDenoiser_);
    const bool profileReady =
        specbleach_denoiser_has_any_profile(noiseDenoiser_);
    const float magnitudeScale = denoiserFftSize > 0
        ? 2.0f / static_cast<float>(denoiserFftSize)
        : 1.0f;
    for (int bin = 0; bin < SpectrumAnalyzer::kBinCount; ++bin) {
        float db = -120.0f;
        if (profileReady && profile && profileSize > 1 && denoiserFftSize > 0) {
            const float source = static_cast<float>(bin) *
                static_cast<float>(denoiserFftSize) /
                static_cast<float>(SpectrumAnalyzer::kFftSize);
            const uint32_t lower = std::min(
                static_cast<uint32_t>(source), profileSize - 1);
            const uint32_t upper = std::min(lower + 1, profileSize - 1);
            const float fraction = source - static_cast<float>(lower);
            const float power = profile[lower] + fraction *
                (profile[upper] - profile[lower]);
            const float normalizedPower = std::max(
                power * magnitudeScale * magnitudeScale, 1.0e-12f);
            db = std::clamp(10.0f * std::log10(normalizedPower),
                            -120.0f, 6.0f);
        }
        noiseSpectrum_[bin].store(db, std::memory_order_relaxed);
    }

    params_.noiseProfileBlocks.store(
        specbleach_denoiser_get_noise_profile_block_count_for_mode(
            noiseDenoiser_, SPECBLEACH_PROFILE_ROLLING_MEAN),
        std::memory_order_relaxed);
    params_.noiseTransientDetected.store(
        specbleach_denoiser_is_transient_detected(noiseDenoiser_),
        std::memory_order_relaxed);
    params_.noiseTransientIntensity.store(
        specbleach_denoiser_get_transient_intensity(noiseDenoiser_),
        std::memory_order_relaxed);
    float peaks[4]{};
    const uint32_t peakCount = specbleach_denoiser_get_tonal_peaks(
        noiseDenoiser_, peaks, 4);
    for (uint32_t i = 0; i < 4; ++i) {
        params_.noiseTonalPeaksHz[i].store(
            i < peakCount ? peaks[i] : 0.0f, std::memory_order_relaxed);
    }
}

void AudioEngine::resetNoiseRepellentProfile() {
    const bool wasRunning = running_.exchange(false, std::memory_order_acq_rel);
    if (wasRunning) closeStreams();

    if (noiseDenoiser_) {
        specbleach_denoiser_reset_noise_profile(noiseDenoiser_);
    }
    params_.noiseProfileReady.store(false, std::memory_order_release);
    params_.noiseLearning.store(false, std::memory_order_relaxed);
    params_.noiseParamsDirty.store(true, std::memory_order_release);

    if (wasRunning) {
        ring_.reset();
        if (openStreams()) {
            running_.store(true, std::memory_order_release);
        } else {
            LOGE("Failed to restart audio after resetting the noise profile");
        }
    }
}

void AudioEngine::setInputDeviceId(int32_t deviceId, bool communicationRoute) {
    const int32_t previous =
        inputDeviceId_.exchange(deviceId, std::memory_order_acq_rel);
    const bool previousCommunication =
        communicationInput_.exchange(communicationRoute, std::memory_order_acq_rel);
    if (previous == deviceId && previousCommunication == communicationRoute) return;

    const bool wasRunning = running_.exchange(false, std::memory_order_acq_rel);
    if (wasRunning) closeStreams();

    // A profile learned from a different microphone is invalid. Clear it
    // while the callback is stopped so the next capture starts cleanly.
    if (noiseDenoiser_) {
        specbleach_denoiser_reset_noise_profile(noiseDenoiser_);
    }
    params_.noiseProfileReady.store(false, std::memory_order_release);
    params_.noiseLearning.store(false, std::memory_order_relaxed);
    params_.noiseParamsDirty.store(true, std::memory_order_release);

    if (wasRunning) {
        ring_.reset();
        if (openStreams()) {
            running_.store(true, std::memory_order_release);
        } else {
            LOGE("Failed to restart audio after changing input device to %d",
                 deviceId);
        }
    }
}

void AudioEngine::rebuildEQ() {
    for (int i = 0; i < kEqBands; i++) {
        float gain = params_.eqGain[i].load(std::memory_order_relaxed);
        if (i == 0)
            eqFilters_[i].setLowShelf(kEqFreqs[i], gain, (float)sampleRate_);
        else if (i == kEqBands - 1)
            eqFilters_[i].setHighShelf(kEqFreqs[i], gain, (float)sampleRate_);
        else
            eqFilters_[i].setPeak(kEqFreqs[i], gain, 1.0f, (float)sampleRate_);
        eqGainCache_[i] = gain;
    }
    eqDirty_ = false;
}

// ─── Audio callback (output stream drives everything) ────────────────────────
oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream, void* audioData, int32_t numFrames) {

    auto* out = static_cast<float*>(audioData);

    if (!running_.load(std::memory_order_acquire)) {
        std::fill(out, out + numFrames, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // Read from input stream (non-blocking, 0ns timeout)
    oboe::ResultWithValue<int32_t> readResult =
        inputStream_->read(out, numFrames, 0);

    int32_t framesRead = (readResult) ? readResult.value() : 0;

    if (framesRead < numFrames) {
        // Zero-fill remaining frames to avoid glitches
        std::fill(out + framesRead, out + numFrames, 0.0f);
        params_.inputShortReadCount.fetch_add(1, std::memory_order_relaxed);
    }

    const bool spectrumEnabled =
        spectrumEnabled_.load(std::memory_order_acquire);
    const bool inputSpectrumPublished = spectrumEnabled
        ? inputSpectrum_.process(out, numFrames)
        : false;

    // Measure input level and signal health (no allocation or locks).
    measureLevel(out, numFrames, params_.inputLevelDb, true);

    // Process effects chain
    if (!params_.masterBypass.load(std::memory_order_relaxed)) {
        processEffectsChain(out, numFrames);
    }

    // Process external plugins
    if (pluginHost_) {
        pluginHost_->process(out, numFrames, sampleRate_);
    }

    const bool outputSpectrumPublished = spectrumEnabled
        ? outputSpectrum_.process(out, numFrames)
        : false;
    if (inputSpectrumPublished || outputSpectrumPublished) {
        publishNoiseDiagnostics();
    }

    // Measure output level
    measureLevel(out, numFrames, params_.outputLevelDb);

    // Always dispatch processed audio to Kotlin monitor (for SoftwareLoopback/metering)
    dispatchToKotlin(out, numFrames);

    // The device output stream must stay silent. The audible "monitor" is produced by
    // SoftwareLoopback's AudioTrack, which is user-toggleable and routed to the chosen
    // output device. Leaving the processed mic in `out` here would play it to the
    // speaker/earpiece unconditionally - i.e. you always hear yourself, even with the
    // monitor switched off. dispatchToKotlin() already copied the buffer, so zeroing it
    // now does not affect the virtual-mic / loopback path.
    std::fill(out, out + numFrames, 0.0f);

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGE("Audio stream error: %s", oboe::convertToText(error));
    if (!running_.load()) return;

    // Backoff: track restarts in a 10-second window
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t nowMs = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;

    if (nowMs - lastRestartMs_ > 10000) {
        // Window expired — reset counter
        restartCount_ = 0;
    }
    lastRestartMs_ = nowMs;
    restartCount_++;

    if (restartCount_ > kMaxRestarts) {
        LOGE("Too many stream restarts (%d) — giving up to avoid su popup loop", restartCount_);
        running_.store(false, std::memory_order_release);
        closeStreams();
        return;
    }

    // Exponential backoff sleep before retry (avoids hammering audio HAL)
    int backoffMs = 100 * (1 << std::min(restartCount_ - 1, 5));
    LOGI("Restarting audio streams (attempt %d/%d, backoff %dms)", restartCount_, kMaxRestarts, backoffMs);
    struct timespec sleep_ts = { backoffMs / 1000, (backoffMs % 1000) * 1000000L };
    nanosleep(&sleep_ts, nullptr);

    closeStreams();
    openStreams();
}

void AudioEngine::measureLevel(const float* buf, int32_t frames,
                               std::atomic<float>& out,
                               bool captureInputDiagnostics) {
    float rms = 0.0f;
    float peak = 0.0f;
    float sum = 0.0f;
    int clipped = 0;
    for (int i = 0; i < frames; i++) {
        const float sample = buf[i];
        rms += sample * sample;
        if (captureInputDiagnostics) {
            peak = std::max(peak, std::abs(sample));
            sum += sample;
            if (std::abs(sample) >= 0.999f) ++clipped;
        }
    }
    rms = std::sqrt(rms / frames);
    // Smooth (IIR, coeff ≈ 0.95 @ 48kHz/128)
    float prev = std::pow(10.0f, out.load(std::memory_order_relaxed) * 0.05f);
    float smooth = prev * 0.95f + rms * 0.05f;
    out.store(linTodB(smooth), std::memory_order_relaxed);

    if (captureInputDiagnostics) {
        const float peakDb = linTodB(peak);
        const float previousPeak =
            params_.inputPeakDb.load(std::memory_order_relaxed);
        params_.inputPeakDb.store(
            std::max(peakDb, previousPeak - 0.75f),
            std::memory_order_relaxed);
        const float blockDc = sum / static_cast<float>(frames);
        const float previousDc =
            params_.inputDcOffset.load(std::memory_order_relaxed);
        params_.inputDcOffset.store(
            previousDc * 0.95f + blockDc * 0.05f,
            std::memory_order_relaxed);
        const float blockClipPercent = 100.0f * static_cast<float>(clipped) /
            static_cast<float>(frames);
        const float previousClip =
            params_.inputClipPercent.load(std::memory_order_relaxed);
        params_.inputClipPercent.store(
            previousClip * 0.90f + blockClipPercent * 0.10f,
            std::memory_order_relaxed);
    }
}

// ─── Effects chain dispatch ───────────────────────────────────────────────────
void AudioEngine::processEffectsChain(float* buf, int32_t frames) {
    const bool noiseEnabled =
        params_.noiseRepellentEnabled.load(std::memory_order_relaxed);
    const bool learning = params_.noiseLearning.load(std::memory_order_relaxed);
    if (noiseEnabled || learning)
        dsp_noiseRepellent(buf, frames);

    if (params_.gateEnabled.load(std::memory_order_relaxed))
        dsp_noiseGate(buf, frames);

    // Check if EQ gains changed (cheap comparison)
    for (int i = 0; i < kEqBands; i++) {
        if (params_.eqGain[i].load(std::memory_order_relaxed) != eqGainCache_[i]) {
            eqDirty_ = true;
            break;
        }
    }
    if (eqDirty_) rebuildEQ();
    if (params_.eqEnabled.load(std::memory_order_relaxed))
        dsp_eq(buf, frames);

    if (params_.compEnabled.load(std::memory_order_relaxed))
        dsp_compressor(buf, frames);

    if (params_.reverbEnabled.load(std::memory_order_relaxed))
        dsp_reverb(buf, frames);

    if (params_.pitchEnabled.load(std::memory_order_relaxed))
        dsp_pitchShift(buf, frames);

    if (noiseEnabled)
        dsp_outputGain(buf, frames);
}

// ─── Noise Repellent (libspecbleach) ────────────────────────────────────────
void AudioEngine::dsp_noiseRepellent(float* buf, int32_t frames) {
    if (!noiseDenoiser_ ||
        !params_.noiseEngineReady.load(std::memory_order_acquire)) {
        return;
    }

    // Parameters are copied between process calls on this same callback
    // thread. Curve storage and the selected adaptive estimator were prewarmed
    // during setup, so this path performs no allocations.
    if (params_.noiseParamsDirty.exchange(false, std::memory_order_acq_rel)) {
        loadNoiseRepellentParams();
    }

    if (specbleach_denoiser_process(
            noiseDenoiser_, static_cast<uint32_t>(frames), buf, buf)) {
        params_.noiseProfileReady.store(
            specbleach_denoiser_has_any_profile(noiseDenoiser_),
            std::memory_order_release);
    }
}

void AudioEngine::dsp_outputGain(float* buf, int32_t frames) {
    const float target = dBToLin(std::clamp(
        params_.noiseOutputGainDb.load(std::memory_order_relaxed),
        0.0f, 30.0f));
    const float smoothing = 1.0f - std::exp(
        -1.0f / (0.020f * static_cast<float>(sampleRate_)));

    for (int i = 0; i < frames; ++i) {
        outputGainSmoothed_ += smoothing * (target - outputGainSmoothed_);
        // Bound the monitor signal even when a quiet stethoscope is boosted
        // heavily. Start with low headphone volume regardless.
        buf[i] = std::clamp(buf[i] * outputGainSmoothed_, -0.98f, 0.98f);
    }
}

// ─── Noise Gate ──────────────────────────────────────────────────────────────
void AudioEngine::dsp_noiseGate(float* buf, int32_t frames) {
    float threshLin  = dBToLin(params_.gateThresholdDb.load(std::memory_order_relaxed));
    float attackCoef = 1.0f - std::exp(-1.0f / (params_.gateAttackMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    float releaseCoef= 1.0f - std::exp(-1.0f / (params_.gateReleaseMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));

    for (int i = 0; i < frames; i++) {
        // RMS with single-pole smoothing
        float x2 = buf[i] * buf[i];
        gateRms_ = gateRms_ * 0.995f + x2 * 0.005f;
        float rms = std::sqrt(gateRms_);

        float target = (rms > threshLin) ? 1.0f : 0.0f;
        float coef   = (target > gateEnv_) ? attackCoef : releaseCoef;
        gateEnv_    += coef * (target - gateEnv_);
        buf[i]      *= gateEnv_;
    }
}

// ─── 10-band EQ ──────────────────────────────────────────────────────────────
void AudioEngine::dsp_eq(float* buf, int32_t frames) {
    for (int i = 0; i < frames; i++) {
        float x = buf[i];
        for (int b = 0; b < kEqBands; b++) {
            x = eqFilters_[b].process(x);
        }
        buf[i] = x;
    }
}

// ─── Feed-forward RMS Compressor with soft knee ──────────────────────────────
void AudioEngine::dsp_compressor(float* buf, int32_t frames) {
    float threshold = params_.compThresholdDb.load(std::memory_order_relaxed);
    float ratio     = params_.compRatio.load(std::memory_order_relaxed);
    float makeup    = dBToLin(params_.compMakeupDb.load(std::memory_order_relaxed));
    float attackCoef = 1.0f - std::exp(-1.0f / (params_.compAttackMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    float releaseCoef= 1.0f - std::exp(-1.0f / (params_.compReleaseMs.load(std::memory_order_relaxed)
                        * 0.001f * sampleRate_));
    static constexpr float kKneeWidth = 6.0f; // dB

    float totalGR = 0.0f;
    for (int i = 0; i < frames; i++) {
        // RMS envelope
        compEnv_ = compEnv_ * (1.0f - attackCoef) + (buf[i]*buf[i]) * attackCoef;
        float levelDb = linTodB(std::sqrt(compEnv_) + 1e-10f);

        // Soft-knee gain computation
        float grDb = 0.0f;
        float over = levelDb - threshold;
        if (over > kKneeWidth * 0.5f) {
            grDb = (over) * (1.0f - 1.0f/ratio);
        } else if (over > -kKneeWidth * 0.5f) {
            float t = (over + kKneeWidth * 0.5f) / kKneeWidth;
            grDb = t * t * (over) * (1.0f - 1.0f/ratio);
        }
        float targetGain = dBToLin(-grDb) * makeup;

        // Smooth gain application
        float coef = (targetGain < 1.0f) ? attackCoef : releaseCoef;
        compGainReduction_ += coef * (targetGain - compGainReduction_);
        buf[i] *= compGainReduction_;
        totalGR += grDb;
    }
    params_.gainReductionDb.store(totalGR / frames, std::memory_order_relaxed);
}

// ─── Schroeder Reverb (4 comb + 2 allpass) ───────────────────────────────────
void AudioEngine::dsp_reverb(float* buf, int32_t frames) {
    float mix     = params_.reverbMix.load(std::memory_order_relaxed);
    float room    = params_.reverbRoomSize.load(std::memory_order_relaxed);
    float damping = params_.reverbDamping.load(std::memory_order_relaxed);

    for (int b = 0; b < 4; b++) {
        combs_[b].feedback = 0.7f + room * 0.27f;
        combs_[b].damp     = damping * 0.4f;
    }

    for (int i = 0; i < frames; i++) {
        float dry = buf[i];
        float wet = 0.0f;
        for (auto& c : combs_)  wet += c.process(dry * 0.015f);
        for (auto& a : aps_)    wet  = a.process(wet);
        buf[i] = dry * (1.0f - mix) + wet * mix;
    }
}

// ─── Granular Pitch Shifter (OLA / time-stretch) ─────────────────────────────
void AudioEngine::dsp_pitchShift(float* buf, int32_t frames) {
    float semitones = params_.pitchSemitones.load(std::memory_order_relaxed);
    float rate      = std::pow(2.0f, semitones / 12.0f); // playback rate

    for (int i = 0; i < frames; i++) {
        // Write input
        pitchIn_[pitchWritePos_ % (kGrainSize*2)] = buf[i];
        pitchWritePos_++;

        // Read at variable rate (linear interpolation)
        int   ri   = (int)pitchReadFrac_;
        float frac = pitchReadFrac_ - ri;
        int   i0   = ri % (kGrainSize*2);
        int   i1   = (ri+1) % (kGrainSize*2);
        buf[i]      = pitchIn_[i0] * (1.0f - frac) + pitchIn_[i1] * frac;

        pitchReadFrac_ += rate;
        // Keep read pointer within 1 grain of write (latency = 1 grain)
        float lag = pitchWritePos_ - pitchReadFrac_;
        if (lag > kGrainSize*2 - 1) pitchReadFrac_ = pitchWritePos_ - kGrainSize;
        if (lag < 1)               pitchReadFrac_ = pitchWritePos_ - kGrainSize;
    }
}

} // namespace micplugin

// ─────────────────────────────────────────────────────────────────────────────
// JNI Bindings
// ─────────────────────────────────────────────────────────────────────────────
using namespace micplugin;

static AudioEngine* gEngine    = nullptr;
static PluginHost*  gPluginHost = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_micplugin_audio_OboeEngine_nativeCreate(JNIEnv*, jobject) {
    auto* engine = new AudioEngine();
    auto* host   = new PluginHost();
    engine->setPluginHost(host);
    gEngine    = engine;
    gPluginHost = host;
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    delete engine;
    delete gPluginHost;
    gEngine     = nullptr;
    gPluginHost = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_micplugin_audio_OboeEngine_nativeStart(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    engine->stop();
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetParam(
    JNIEnv*, jobject, jlong handle, jint effectId, jint paramId, jfloat value) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    auto& p = engine->params();
    switch (effectId) {
        case 0: // Gate
            if (paramId==0) p.gateThresholdDb.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.gateAttackMs.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.gateReleaseMs.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.gateEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 1: // EQ
            if (paramId >= 0 && paramId < 10)
                p.eqGain[paramId].store(value, std::memory_order_relaxed);
            else if (paramId == 10) p.eqEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 2: // Compressor
            if (paramId==0) p.compThresholdDb.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.compRatio.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.compAttackMs.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.compReleaseMs.store(value, std::memory_order_relaxed);
            else if (paramId==4) p.compMakeupDb.store(value, std::memory_order_relaxed);
            else if (paramId==5) p.compEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 3: // Reverb
            if (paramId==0) p.reverbMix.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.reverbRoomSize.store(value, std::memory_order_relaxed);
            else if (paramId==2) p.reverbDamping.store(value, std::memory_order_relaxed);
            else if (paramId==3) p.reverbEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 4: // Pitch
            if (paramId==0) p.pitchSemitones.store(value, std::memory_order_relaxed);
            else if (paramId==1) p.pitchEnabled.store(value > 0.5f, std::memory_order_relaxed);
            break;
        case 5: // Noise Repellent
            if (paramId==0) p.noiseRepellentEnabled.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId==1) p.noiseLearning.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId==2) p.noiseReductionDb.store(std::clamp(value, 0.0f, 40.0f), std::memory_order_relaxed);
            else if (paramId==3) p.noiseSmoothing.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
            else if (paramId==4) p.noiseMasking.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
            else if (paramId==5) p.noiseWhitening.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
            else if (paramId==6) p.noiseAggressiveness.store(std::clamp(value, -1.0f, 1.0f), std::memory_order_relaxed);
            else if (paramId==7) p.noiseResidualListen.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId==8) p.noiseOutputGainDb.store(std::clamp(value, 0.0f, 30.0f), std::memory_order_relaxed);
            else if (paramId==9) p.noiseProfileOffsetDb.store(std::clamp(value, -12.0f, 12.0f), std::memory_order_relaxed);
            else if (paramId==10) p.noiseTransientProtection.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId==11) p.noiseTonalReductionDb.store(std::clamp(value, 0.0f, 40.0f), std::memory_order_relaxed);
            else if (paramId==12) p.noiseSmoothingMode.store(std::clamp(static_cast<int>(value), 0, 3), std::memory_order_relaxed);
            else if (paramId==13) p.noiseDfttStrength.store(std::clamp(value, 0.0f, 4.0f), std::memory_order_relaxed);
            else if (paramId==14) p.noiseSuppressionStrength.store(std::clamp(value, 0.0f, 1.0f), std::memory_order_relaxed);
            else if (paramId==15) p.noiseTonalProfileOffsetDb.store(std::clamp(value, -12.0f, 12.0f), std::memory_order_relaxed);
            else if (paramId==16) p.noiseAdaptive.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId==17) p.noiseCurveEnabled.store(value > 0.5f, std::memory_order_relaxed);
            else if (paramId>=18 && paramId<28) p.noiseCurveBiasDb[paramId - 18].store(std::clamp(value, -15.0f, 15.0f), std::memory_order_relaxed);
            else if (paramId==28) engine->setNoiseQualityMode(static_cast<int>(value));
            else if (paramId==29) engine->setNoiseEstimatorMethod(static_cast<int>(value));
            p.noiseParamsDirty.store(true, std::memory_order_release);
            break;
        case 99: // Master bypass
            p.masterBypass.store(value > 0.5f, std::memory_order_relaxed);
            break;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetLevels(JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    auto& p = engine->params();
    float levels[3] = {
        p.inputLevelDb.load(std::memory_order_relaxed),
        p.outputLevelDb.load(std::memory_order_relaxed),
        p.gainReductionDb.load(std::memory_order_relaxed)
    };
    jfloatArray arr = env->NewFloatArray(3);
    env->SetFloatArrayRegion(arr, 0, 3, levels);
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_micplugin_audio_OboeEngine_nativeLoadPlugin(
    JNIEnv* env, jobject, jlong handle, jstring soPath, jint formatId) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    if (!gPluginHost) return 0;
    const char* path = env->GetStringUTFChars(soPath, nullptr);
    jlong result = gPluginHost->loadPlugin(path, formatId, engine->sampleRate());
    env->ReleaseStringUTFChars(soPath, path);
    return result;
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeUnloadPlugin(
    JNIEnv*, jobject, jlong handle, jlong pluginHandle) {
    if (gPluginHost) gPluginHost->unloadPlugin(pluginHandle);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetPluginParam(
    JNIEnv*, jobject, jlong handle, jlong pluginHandle, jint paramId, jfloat value) {
    if (gPluginHost) gPluginHost->setPluginParam(pluginHandle, paramId, value);
}

JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetSampleRate(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->sampleRate();
}

JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetFramesPerBurst(JNIEnv*, jobject, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->framesPerBurst();
}

JNIEXPORT jfloatArray JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetNoiseRepellentStatus(
    JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    auto& p = engine->params();
    const int qualityMode = p.noiseQualityMode.load(std::memory_order_relaxed);
    float status[22] = {
        p.noiseEngineReady.load(std::memory_order_acquire) ? 1.0f : 0.0f,
        p.noiseProfileReady.load(std::memory_order_acquire) ? 1.0f : 0.0f,
        static_cast<float>(p.noiseLatencyFrames.load(std::memory_order_relaxed)),
        static_cast<float>(p.noiseFrameSize.load(std::memory_order_relaxed)),
        static_cast<float>(p.noiseFftSize.load(std::memory_order_relaxed)),
        static_cast<float>(p.noiseHopSize.load(std::memory_order_relaxed)),
        static_cast<float>(p.noiseProfileSize.load(std::memory_order_relaxed)),
        static_cast<float>(p.noiseProfileBlocks.load(std::memory_order_relaxed)),
        p.noiseTransientDetected.load(std::memory_order_relaxed) ? 1.0f : 0.0f,
        p.noiseTransientIntensity.load(std::memory_order_relaxed),
        p.inputPeakDb.load(std::memory_order_relaxed),
        p.inputDcOffset.load(std::memory_order_relaxed),
        p.inputClipPercent.load(std::memory_order_relaxed),
        static_cast<float>(p.inputShortReadCount.load(std::memory_order_relaxed)),
        static_cast<float>(qualityMode),
        static_cast<float>(qualityMode == 0 ? SPECBLEACH_SMOOTHING_TEMPORAL :
            p.noiseSmoothingMode.load(std::memory_order_relaxed)),
        p.noiseAdaptive.load(std::memory_order_relaxed) ? 1.0f : 0.0f,
        p.noiseTonalPeaksHz[0].load(std::memory_order_relaxed),
        p.noiseTonalPeaksHz[1].load(std::memory_order_relaxed),
        p.noiseTonalPeaksHz[2].load(std::memory_order_relaxed),
        p.noiseTonalPeaksHz[3].load(std::memory_order_relaxed),
        static_cast<float>(engine->getInputDeviceId()),
    };
    jfloatArray arr = env->NewFloatArray(22);
    env->SetFloatArrayRegion(arr, 0, 22, status);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetSpectrum(
    JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    constexpr int kValueCount = 3 + SpectrumAnalyzer::kBinCount * 3;
    std::array<float, kValueCount> values{};
    engine->copySpectrumSnapshot(values.data());
    jfloatArray arr = env->NewFloatArray(kValueCount);
    env->SetFloatArrayRegion(arr, 0, kValueCount, values.data());
    return arr;
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetSpectrumEnabled(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    if (engine) engine->setSpectrumEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeResetNoiseRepellentProfile(
    JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    if (engine) engine->resetNoiseRepellentProfile();
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetInjectionMode(
    JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* engine = reinterpret_cast<AudioEngine*>(handle);
    engine->params().injectionMode.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    LOGI("Injection mode: %s", enabled ? "ON (Shizuku/Root)" : "OFF (software loopback)");
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetInputDevice(
    JNIEnv*, jobject, jlong handle, jint deviceId, jboolean communicationRoute) {
    auto* engine = reinterpret_cast<micplugin::AudioEngine*>(handle);
    if (engine) {
        engine->setInputDeviceId(deviceId, communicationRoute == JNI_TRUE);
    }
    LOGI("Input device set to %d (communication=%s)", deviceId,
         communicationRoute == JNI_TRUE ? "true" : "false");
}

JNIEXPORT void JNICALL
Java_com_micplugin_audio_OboeEngine_nativeSetMonitorCallback(
    JNIEnv* env, jobject /*thiz*/, jlong /*handle*/, jobject callback) {

    env->GetJavaVM(&gJvm);

    if (gMonitorCallback) { env->DeleteGlobalRef(gMonitorCallback); gMonitorCallback = nullptr; }

    if (callback) {
        gMonitorCallback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(gMonitorCallback);
        gInvokeMethod = env->GetMethodID(cls, "invoke",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    }
}



JNIEXPORT jstring JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetPluginParams(
    JNIEnv* env, jobject, jlong /*engineHandle*/, jlong pluginHandle) {
    if (!gPluginHost) return env->NewStringUTF("[]");
    auto inst = gPluginHost->findPlugin(pluginHandle);
    if (!inst) return env->NewStringUTF("[]");

    std::string json;
    switch (inst->format) {
        case PluginFormat::CLAP: json = ClapHost::getParams(inst); break;
        case PluginFormat::LV2:  json = Lv2Host::getParams(inst);  break;
        case PluginFormat::VST3: json = Vst3Bridge::getParams(inst); break;
        default: json = "[]"; break;
    }
    return env->NewStringUTF(json.c_str());
}



JNIEXPORT jint JNICALL
Java_com_micplugin_audio_OboeEngine_nativeGetInputSessionId(
    JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<micplugin::AudioEngine*>(handle);
    return engine ? engine->getInputSessionId() : -1;
}


} // extern "C"
