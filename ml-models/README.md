# Unused Whisper models

These are kept out of `app/src/main/assets` so they aren't packaged into the APK. The app only
loads `assets/whisper-tiny-en.tflite` with `assets/filters_vocab_en.bin`.

| File | What it is |
|---|---|
| `whisper-tiny.en.tflite` | Another TFLite export of Whisper tiny.en |
| `whisper-base.en.tflite` | Whisper base.en, larger and slower (not in git; see `.gitignore`) |
| `Whisper-Tiny-En_WhisperEncoderInf.tflite` | Qualcomm AI Hub encoder-only export, for a possible NPU version |
| `filters_vocab_multilingual.bin` | Vocab and mel filters for the multilingual models |

To try one, copy it back into `app/src/main/assets` and change the model name passed to
`Whisper.loadModelFromAssets` in the reader fragments.
