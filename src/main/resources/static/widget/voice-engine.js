 /**
 * Voice Engine - Full-Duplex VAD, State Machine, Barge-In & Audio Context Player
 * Integrates with Vercel AI Gateway / Fish Audio models (s2.1-pro-free & transcribe-1-free)
 */

export const VoiceState = {
    IDLE: 'IDLE',
    MIC_PERMISSION: 'MIC_PERMISSION',
    READY: 'READY',
    LISTENING: 'LISTENING',
    PROCESSING: 'PROCESSING',
    THINKING: 'THINKING',
    SPEAKING: 'SPEAKING',
    INTERRUPTED: 'INTERRUPTED',
    ERROR: 'ERROR'
};

export class VoiceEngine {
    constructor(apiClient, callbacks = {}) {
        this.api = apiClient;
        this.callbacks = {
            onStateChange: callbacks.onStateChange || (() => {}),
            onVolumeChange: callbacks.onVolumeChange || (() => {}),
            onTranscript: callbacks.onTranscript || (() => {}),
            onResponse: callbacks.onResponse || (() => {}),
            onError: callbacks.onError || (() => {})
        };

        this.currentState = VoiceState.IDLE;
        this.mediaStream = null;
        this.mediaRecorder = null;
        this.audioContext = null;
        this.analyser = null;
        this.audioChunks = [];
        this.activeAudioElement = null;
        this.sessionId = null;
        this.silenceTimer = null;
        this.maxTurnTimer = null;
        this.isVoiceModeActive = false;

        // Language Configuration (English-only voice assistant)
        this.currentLang = 'en-US';

        // VAD Tuning parameters (Optimized for lightning-fast sub-second conversational latency)
        this.SILENCE_THRESHOLD_DB = -42; // dB
        this.SILENCE_DURATION_MS = 900; // ms of silence to finish user's query turn (fast conversational cutoff)
        this.MIN_SPEECH_DURATION_MS = 350; // ms to allow quick responses ("yes", "hi", "pricing")
        this.speechStartTime = 0;
        this.hasSpoken = false;
        this.vadCheckInterval = null;
    }

    setLanguage(langCode) {
        this.currentLang = langCode || 'en-US';
        if (this.activeSpeechRec) {
            try {
                this.activeSpeechRec.lang = this.currentLang;
            } catch (e) {}
        }
        console.log('🎙️ [VoiceEngine] Speech recognition language set to:', this.currentLang);
    }

    getLanguage() {
        return this.currentLang;
    }

    setState(newState, payload = {}) {
        this.currentState = newState;
        this.callbacks.onStateChange(newState, payload);
    }

    getState() {
        return this.currentState;
    }

    async initAudioContext() {
        if (!this.audioContext) {
            const AudioCtx = window.AudioContext || window.webkitAudioContext;
            this.audioContext = new AudioCtx();
        }
        if (this.audioContext.state === 'suspended') {
            await this.audioContext.resume();
        }
    }

    async startVoiceMode() {
        this.isVoiceModeActive = true;
        this.setState(VoiceState.MIC_PERMISSION, { message: 'Requesting microphone permission...' });

        // Security check: Modern browsers require HTTPS or localhost for microphone access
        if (typeof window !== 'undefined' && window.isSecureContext === false &&
            window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
            const errMsg = 'Microphone requires a secure HTTPS connection or http://localhost. Current origin is not secure.';
            console.error('🎙️ [VoiceEngine] Insecure context:', errMsg);
            this.setState(VoiceState.ERROR, { message: errMsg });
            this.callbacks.onError(errMsg);
            return;
        }

        // Check explicit browser permission state if supported
        if (navigator.permissions && navigator.permissions.query) {
            try {
                const permStatus = await navigator.permissions.query({ name: 'microphone' });
                if (permStatus.state === 'denied') {
                    const errMsg = 'Microphone permission is blocked in your browser. Click the lock / tune icon in your address bar to allow microphone access.';
                    console.warn('🎙️ [VoiceEngine] Permission explicitly denied by browser settings');
                    this.setState(VoiceState.ERROR, { message: errMsg });
                    this.callbacks.onError(errMsg);
                    return;
                }
            } catch (permErr) {
                // query({ name: 'microphone' }) not supported on some browsers (e.g. Firefox); continue to getUserMedia
            }
        }

        // Tier 1: Try getUserMedia
        let stream = null;
        let permissionError = null;

        if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
            try {
                stream = await navigator.mediaDevices.getUserMedia({
                    audio: {
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                    }
                });
            } catch (err1) {
                console.warn('Advanced audio constraints failed:', err1.name, err1.message);
                if (err1.name === 'NotAllowedError' || err1.name === 'PermissionDeniedError' || err1.name === 'SecurityError') {
                    permissionError = err1;
                } else {
                    try {
                        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                    } catch (err2) {
                        console.warn('Basic getUserMedia failed:', err2.name, err2.message);
                        permissionError = err2;
                    }
                }
            }
        } else {
            console.warn('navigator.mediaDevices.getUserMedia is not available');
        }

        if (stream) {
            this.mediaStream = stream;
            try {
                await this.initAudioContext();
                this.setupVAD();
                this.setState(VoiceState.READY);
                this.startListening();
                return;
            } catch (audioCtxErr) {
                console.warn('AudioContext setup error:', audioCtxErr);
            }
        }

        // If user or system explicitly blocked / denied permission, do not try SpeechRecognition
        if (permissionError) {
            let errMsg = 'Microphone permission blocked. Please allow microphone access in your browser.';
            if (permissionError.name === 'NotAllowedError' || permissionError.name === 'PermissionDeniedError') {
                errMsg = 'Microphone access blocked. Click the tune/lock icon in your address bar, allow microphone, and click "Retry Microphone".';
            } else if (permissionError.name === 'NotFoundError' || permissionError.name === 'DevicesNotFoundError') {
                errMsg = 'No microphone device found on your device. Please connect a microphone and retry.';
            } else if (permissionError.name === 'NotReadableError' || permissionError.name === 'TrackStartError') {
                errMsg = 'Microphone is currently in use by another application. Please close other audio apps and retry.';
            } else if (permissionError.name === 'SecurityError') {
                errMsg = 'Microphone blocked by security or iframe permissions policy (ensure allow="microphone *" is enabled).';
            }

            console.error('🎙️ [VoiceEngine] Microphone error:', permissionError.name, errMsg);
            this.setState(VoiceState.ERROR, { message: errMsg });
            this.callbacks.onError(errMsg);
            return;
        }

        // Tier 2: Try Web Speech API fallback only if getUserMedia is not implemented
        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SpeechRec) {
            console.log('🎙️ Using Web Speech API fallback for voice input');
            this.startWebSpeechFallback();
            return;
        }

        // Tier 3: Error state if all audio channels fail
        const errMsg = 'Microphone is not supported or accessible in this browser.';
        this.setState(VoiceState.ERROR, { message: errMsg });
        this.callbacks.onError(errMsg);
    }

    startWebSpeechFallback() {
        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!SpeechRec) return;

        this.stopAudioPlayback();
        const recognition = new SpeechRec();
        recognition.continuous = false;
        recognition.interimResults = false;
        recognition.lang = this.currentLang || 'en-US';

        this.setState(VoiceState.LISTENING);

        recognition.onstart = () => {
            this.setState(VoiceState.LISTENING);
        };

        recognition.onresult = async (event) => {
            const transcript = event.results[0][0].transcript;
            console.log('🎙️ [WebSpeech] Recognized speech:', transcript);
            this.setState(VoiceState.PROCESSING);
            await this.processRecordedAudio(null, transcript);
        };

        recognition.onerror = (e) => {
            console.warn('WebSpeech error:', e.error);
            if (e.error === 'not-allowed' || e.error === 'service-not-allowed' || e.error === 'audio-capture') {
                this.setState(VoiceState.ERROR, { message: 'Microphone permission blocked. Click the lock/settings icon in your address bar to allow microphone.' });
            } else if (e.error === 'no-speech') {
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => this.startWebSpeechFallback(), 500);
                }
            } else {
                this.setState(VoiceState.ERROR, { message: `Speech recognition error: ${e.error}` });
            }
        };

        recognition.onend = () => {
            if (this.currentState === VoiceState.LISTENING) {
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => this.startWebSpeechFallback(), 500);
                }
            }
        };

        try {
            recognition.start();
        } catch (e) {
            console.warn('Could not start WebSpeech:', e);
            this.setState(VoiceState.ERROR, { message: 'Failed to start speech recognition: ' + e.message });
        }
    }

    setupVAD() {
        if (!this.audioContext || !this.mediaStream) return;

        const source = this.audioContext.createMediaStreamSource(this.mediaStream);
        this.analyser = this.audioContext.createAnalyser();
        this.analyser.fftSize = 512;
        this.analyser.smoothingTimeConstant = 0.4;
        source.connect(this.analyser);

        const bufferLength = this.analyser.frequencyBinCount;
        const dataArray = new Uint8Array(bufferLength);

        if (this.vadCheckInterval) clearInterval(this.vadCheckInterval);

        this.vadCheckInterval = setInterval(() => {
            if (!this.analyser) return;
            this.analyser.getByteFrequencyData(dataArray);

            let sum = 0;
            for (let i = 0; i < bufferLength; i++) {
                sum += dataArray[i];
            }
            const average = sum / bufferLength;
            const normalizedVol = Math.min(100, Math.round((average / 128) * 100));
            this.callbacks.onVolumeChange(normalizedVol);

            // When bot is speaking, ignore microphone audio to prevent bot from interrupting itself
            if (this.currentState === VoiceState.SPEAKING) {
                return;
            }

            // VAD Speech / Silence Trigger
            if (this.currentState === VoiceState.LISTENING) {
                if (normalizedVol > 22) {
                    if (!this.hasSpoken) {
                        this.hasSpoken = true;
                        this.speechStartTime = Date.now();
                        console.log('🎙️ [VoiceEngine] User started speaking (vol:', normalizedVol, ')');
                    }
                    // Reset silence timer because user is speaking
                    if (this.silenceTimer) {
                        clearTimeout(this.silenceTimer);
                        this.silenceTimer = null;
                    }
                } else if (this.hasSpoken) {
                    // Speech has started, now quiet -> start silence counter
                    if (!this.silenceTimer) {
                        this.silenceTimer = setTimeout(() => {
                            const speechDuration = Date.now() - this.speechStartTime;
                            if (speechDuration >= this.MIN_SPEECH_DURATION_MS) {
                                this.finishListening();
                            }
                        }, this.SILENCE_DURATION_MS);
                    }
                }
            }
        }, 60);
    }

    startListening() {
        if (!this.mediaStream) return;

        // If bot is currently speaking or processing, do not start listening until completed
        if (this.currentState === VoiceState.SPEAKING || this.currentState === VoiceState.PROCESSING || this.currentState === VoiceState.THINKING) {
            console.log('🎙️ [VoiceEngine] Bot is speaking/processing. Will listen once playback completes.');
            return;
        }

        // Reset prior audio
        this.stopAudioPlayback();
        this.audioChunks = [];
        this.hasSpoken = false;

        let mimeType = 'audio/webm;codecs=opus';
        if (!MediaRecorder.isTypeSupported(mimeType)) {
            mimeType = MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : '';
        }

        try {
            this.mediaRecorder = mimeType ? new MediaRecorder(this.mediaStream, { mimeType }) : new MediaRecorder(this.mediaStream);
        } catch (e) {
            this.mediaRecorder = new MediaRecorder(this.mediaStream);
        }

        this.mediaRecorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) {
                this.audioChunks.push(e.data);
            }
        };

        this.mediaRecorder.onstop = () => {
            const audioBlob = new Blob(this.audioChunks, { type: this.mediaRecorder.mimeType || 'audio/webm' });
            this.processRecordedAudio(audioBlob, this.liveTranscript || null);
        };

        // Run live speech recognition in parallel to guarantee high-accuracy transcript even if server STT gateway fails
        this.liveTranscript = '';
        const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
        if (SpeechRec) {
            try {
                this.activeSpeechRec = new SpeechRec();
                this.activeSpeechRec.continuous = true;
                this.activeSpeechRec.interimResults = true;
                this.activeSpeechRec.lang = this.currentLang || 'en-US';
                this.activeSpeechRec.onresult = (event) => {
                    let fullText = '';
                    for (let i = 0; i < event.results.length; i++) {
                        fullText += event.results[i][0].transcript + ' ';
                    }
                    this.liveTranscript = fullText.trim();
                    if (this.liveTranscript) {
                        this.hasSpoken = true;
                        this.speechStartTime = this.speechStartTime || Date.now();
                        this.callbacks.onTranscript(this.liveTranscript);
                        // Reset silence timer on fresh speech text
                        if (this.silenceTimer) {
                            clearTimeout(this.silenceTimer);
                            this.silenceTimer = null;
                        }
                    }
                };

                this.activeSpeechRec.onspeechend = () => {
                    if (this.hasSpoken && this.liveTranscript && this.currentState === VoiceState.LISTENING) {
                        if (!this.silenceTimer) {
                            this.silenceTimer = setTimeout(() => {
                                if (this.currentState === VoiceState.LISTENING) {
                                    this.finishListening();
                                }
                            }, 500);
                        }
                    }
                };

                this.activeSpeechRec.start();
            } catch (e) {
                // Ignore speech rec start errors if already running
            }
        }

        this.mediaRecorder.start(250);
        this.setState(VoiceState.LISTENING);

        // Maximum 45s turn timer safety
        if (this.maxTurnTimer) clearTimeout(this.maxTurnTimer);
        this.maxTurnTimer = setTimeout(() => {
            if (this.currentState === VoiceState.LISTENING) {
                this.finishListening();
            }
        }, 45000);
    }

    finishListening() {
        if (this.silenceTimer) clearTimeout(this.silenceTimer);
        if (this.maxTurnTimer) clearTimeout(this.maxTurnTimer);

        if (this.activeSpeechRec) {
            try { this.activeSpeechRec.stop(); } catch (e) {}
            this.activeSpeechRec = null;
        }

        if (this.mediaRecorder && this.mediaRecorder.state === 'recording') {
            this.mediaRecorder.stop();
            this.setState(VoiceState.THINKING);
        }
    }

    async processRecordedAudio(audioBlob, transcriptOverride) {
        if (!this.isVoiceModeActive) {
            console.log('🛑 [VoiceEngine] Voice mode is inactive. Discarding recorded audio.');
            return;
        }

        const transcript = transcriptOverride || this.liveTranscript || null;
        const hasText = transcript && transcript.trim().length > 0;

        // If user never actually spoke above volume threshold and no text recognized, do not send room noise to backend
        if (!this.hasSpoken && !hasText) {
            console.log('🎙️ [VoiceEngine] No active speech detected in turn. Resuming listening...');
            this.setState(VoiceState.LISTENING);
            if (this.isVoiceModeActive) {
                setTimeout(() => {
                    if (this.mediaStream) this.startListening();
                    else this.startWebSpeechFallback();
                }, 300);
            }
            return;
        }

        if (!hasText && (!audioBlob || audioBlob.size < 5000)) {
            this.setState(VoiceState.READY);
            if (this.isVoiceModeActive) {
                setTimeout(() => {
                    if (this.mediaStream) this.startListening();
                    else this.startWebSpeechFallback();
                }, 400);
            }
            return;
        }

        this.setState(VoiceState.PROCESSING);

        try {
            // Speed Optimization: If transcript is already recognized by browser SpeechRecognition,
            // don't upload large audio binary over network. Saves 500ms-1500ms transfer time.
            const payloadBlob = hasText ? null : audioBlob;
            const result = await this.api.sendVoiceTurn(payloadBlob, this.sessionId, null, transcript, this.currentLang || 'en-US');
            if (result && result.sessionId) {
                this.sessionId = result.sessionId;
            }
            if (result && result.turnNumber) {
                this.currentTurnNumber = result.turnNumber;
            }

            if (result && result.cancelled) {
                console.log('🛑 [VoiceEngine] Turn was cancelled/invalidated by barge-in. Discarding audio.');
                return;
            }

            this.callbacks.onResponse(result);

            if (result && result.audioBase64) {
                await this.playBotAudio(result.audioBase64, result.botResponseText, result.detectedLanguage);
            } else if (result && result.botResponseText && typeof window !== 'undefined' && 'speechSynthesis' in window) {
                await this.speakWithWebSpeech(result.botResponseText, result.detectedLanguage);
            } else {
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => {
                        if (this.mediaStream) this.startListening();
                        else this.startWebSpeechFallback();
                    }, 600);
                }
            }
        } catch (err) {
            console.error('Voice processing error:', err);
            this.setState(VoiceState.ERROR, { message: err.message });
            this.callbacks.onError('Voice assistant is temporarily busy. Retrying...');
            setTimeout(() => {
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    if (this.mediaStream) this.startListening();
                    else this.startWebSpeechFallback();
                }
            }, 2000);
        }
    }

    async speakWithWebSpeech(text, lang) {
        this.stopAudioPlayback();
        this.setState(VoiceState.SPEAKING);

        return new Promise((resolve) => {
            if (!window.speechSynthesis) {
                this.setState(VoiceState.READY);
                resolve();
                return;
            }

            window.speechSynthesis.cancel();
            const utterance = new SpeechSynthesisUtterance(text);
            utterance.lang = (lang === 'hi') ? 'hi-IN' : 'en-US';

            // Select warm, natural ~24-year-old female voice matching accent
            const voices = window.speechSynthesis.getVoices() || [];
            let femaleVoice = null;
            if (lang === 'hi') {
                femaleVoice = voices.find(v => (v.lang.includes('hi') || v.lang.includes('IN')) && (v.name.includes('Swara') || v.name.includes('Heera') || v.name.includes('Neerja') || v.name.includes('Google हिन्दी') || v.name.includes('Female')))
                           || voices.find(v => v.lang.includes('hi') || v.lang.includes('IN'));
            } else {
                femaleVoice = voices.find(v => (v.lang.includes('en') || v.lang.includes('IN')) && (v.name.includes('Jenny') || v.name.includes('Aria') || v.name.includes('Swara') || v.name.includes('Priya') || v.name.includes('Natural') || v.name.includes('Online')))
                           || voices.find(v => (v.name.toLowerCase().includes('female') || v.name.includes('Zira') || v.name.includes('Samantha')))
                           || voices.find(v => v.lang.includes('en'));
            }
            if (femaleVoice) {
                utterance.voice = femaleVoice;
            }

            // Natural, youthful (age ~24), pleasant conversational pitch and pacing
            utterance.pitch = 1.18;
            utterance.rate = 1.02;

            utterance.onend = () => {
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => {
                        if (this.mediaStream) this.startListening();
                        else this.startWebSpeechFallback();
                    }, 400);
                }
                resolve();
            };

            utterance.onerror = (err) => {
                console.warn('SpeechSynthesis playback warning:', err);
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => {
                        if (this.mediaStream) this.startListening();
                        else this.startWebSpeechFallback();
                    }, 400);
                }
                resolve();
            };

            window.speechSynthesis.speak(utterance);
        });
    }

    base64ToBlob(base64, mimeType = 'audio/mp3') {
        const byteCharacters = atob(base64);
        const byteNumbers = new Array(byteCharacters.length);
        for (let i = 0; i < byteCharacters.length; i++) {
            byteNumbers[i] = byteCharacters.charCodeAt(i);
        }
        const byteArray = new Uint8Array(byteNumbers);
        return new Blob([byteArray], { type: mimeType });
    }

    async playBotAudio(base64Audio, fallbackText, fallbackLang) {
        this.stopAudioPlayback();
        this.setState(VoiceState.SPEAKING);

        return new Promise((resolve) => {
            let audioUrl = '';
            try {
                const blob = this.base64ToBlob(base64Audio, 'audio/mp3');
                audioUrl = URL.createObjectURL(blob);
            } catch (e) {
                audioUrl = `data:audio/mp3;base64,${base64Audio}`;
            }

            const audio = new Audio(audioUrl);
            this.activeAudioElement = audio;

            audio.onended = () => {
                if (audioUrl.startsWith('blob:')) URL.revokeObjectURL(audioUrl);
                this.activeAudioElement = null;
                this.setState(VoiceState.READY);
                if (this.isVoiceModeActive) {
                    setTimeout(() => {
                        if (this.mediaStream) this.startListening();
                        else this.startWebSpeechFallback();
                    }, 400);
                }
                resolve();
            };

            audio.onerror = (e) => {
                console.warn('Audio element error, falling back to WebSpeech:', e);
                if (audioUrl.startsWith('blob:')) URL.revokeObjectURL(audioUrl);
                this.activeAudioElement = null;
                if (fallbackText && typeof window !== 'undefined' && 'speechSynthesis' in window) {
                    this.speakWithWebSpeech(fallbackText, fallbackLang).then(resolve);
                } else {
                    this.setState(VoiceState.READY);
                    resolve();
                }
            };

            audio.play().catch((err) => {
                console.warn('Audio play error, falling back to WebSpeech:', err);
                if (audioUrl.startsWith('blob:')) URL.revokeObjectURL(audioUrl);
                this.activeAudioElement = null;
                if (fallbackText && typeof window !== 'undefined' && 'speechSynthesis' in window) {
                    this.speakWithWebSpeech(fallbackText, fallbackLang).then(resolve);
                } else {
                    this.setState(VoiceState.READY);
                    resolve();
                }
            });
        });
    }

    handleBargeIn() {
        console.log('🛑 [Barge-In] User interrupted bot speech. Halting playback.');
        this.stopAudioPlayback();
        this.setState(VoiceState.INTERRUPTED);

        if (this.sessionId) {
            this.api.sendBargeIn(this.sessionId, this.currentTurnNumber || 0).catch(() => {});
        }

        setTimeout(() => {
            if (this.mediaStream) this.startListening();
            else this.startWebSpeechFallback();
        }, 150);
    }

    stopAudioPlayback() {
        if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
            try { window.speechSynthesis.cancel(); } catch (e) {}
        }
        if (this.activeAudioElement) {
            try {
                this.activeAudioElement.pause();
                this.activeAudioElement.currentTime = 0;
            } catch (e) {}
            this.activeAudioElement = null;
        }
    }

    stopVoiceMode() {
        console.log('🛑 [VoiceEngine] stopVoiceMode() invoked - shutting down voice immediately');
        this.isVoiceModeActive = false;
        this.stopAudioPlayback();

        if (this.silenceTimer) { clearTimeout(this.silenceTimer); this.silenceTimer = null; }
        if (this.maxTurnTimer) { clearTimeout(this.maxTurnTimer); this.maxTurnTimer = null; }
        if (this.vadCheckInterval) { clearInterval(this.vadCheckInterval); this.vadCheckInterval = null; }

        if (this.activeSpeechRec) {
            try { this.activeSpeechRec.abort(); } catch (e) {}
            this.activeSpeechRec = null;
        }

        if (this.mediaRecorder) {
            try {
                // Detach event listeners so stopping does NOT trigger processRecordedAudio
                this.mediaRecorder.onstop = null;
                this.mediaRecorder.ondataavailable = null;
                if (this.mediaRecorder.state !== 'inactive') {
                    this.mediaRecorder.stop();
                }
            } catch (e) {}
            this.mediaRecorder = null;
        }

        if (this.mediaStream) {
            try {
                this.mediaStream.getTracks().forEach((track) => track.stop());
            } catch (e) {}
            this.mediaStream = null;
        }

        if (this.audioContext && this.audioContext.state !== 'closed') {
            try {
                this.audioContext.close();
            } catch (e) {}
            this.audioContext = null;
        }

        this.audioChunks = [];
        this.liveTranscript = '';
        this.hasSpoken = false;

        this.setState(VoiceState.IDLE);
    }
}
