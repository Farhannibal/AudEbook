package com.example.audebook.asr;

import android.content.Context;
import android.util.Log;

import com.example.audebook.engine.WhisperEngine;
import com.example.audebook.engine.WhisperEngineJava;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(String result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_FILE_NOT_FOUND = "Input file doesn't exist..!";

    // Bundled in the APK's assets
    public static final String MODEL_ASSET = "whisper-tiny-en.tflite";
    public static final String VOCAB_ASSET = "filters_vocab_en.bin";

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;

    private enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private final WhisperEngine mWhisperEngine;
    private final Thread mFileThread;
    private Action mAction;
    private String mWavFilePath;
    private WhisperListener mUpdateListener;

    // Applied by the worker just before its next transcription, so it never races an inference
    private volatile int mRequestedThreads = 0;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    public Whisper(Context context) {
        this.mWhisperEngine = new WhisperEngineJava(context);
//        this.mWhisperEngine = new WhisperEngineNative(context);

        // Start thread for file transcription for file transcription
        mFileThread = new Thread(this::transcribeFileLoop);
        mFileThread.start();
    }

    /**
     * Stops the worker thread and frees the model. Waits for a transcription in progress to
     * finish first, so call this off the main thread. The instance can't be used afterwards.
     */
    public void release() {
        mFileThread.interrupt();
        synchronized (mWhisperEngine) {
            mWhisperEngine.deinitialize();
        }
    }

    /**
     * Older versions copied every model and vocab file from assets into app storage before
     * loading them. The model is now read straight from the APK, so delete those copies.
     */
    public static void deleteLegacyModelCopies(File dir) {
        File[] copies = dir.listFiles((d, name) ->
                name.endsWith(".tflite") || (name.startsWith("filters_vocab") && name.endsWith(".bin")));
        if (copies == null) return;
        for (File copy : copies) {
            if (copy.delete()) {
                Log.d(TAG, "Deleted old model copy " + copy.getName());
            }
        }
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        loadModel(modelPath.getAbsolutePath(), vocabPath.getAbsolutePath(), isMultilingual);
    }

    public void loadModel(String modelPath, String vocabPath, boolean isMultilingual) {
        try {
            mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
    }

    /** Loads a model and vocab bundled in the APK's assets, without copying them to storage. */
    public void loadModelFromAssets(String modelAsset, String vocabAsset, boolean isMultilingual) {
        try {
            mWhisperEngine.initializeFromAssets(modelAsset, vocabAsset, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model from assets...", e);
            sendUpdate("Model initialization failed");
        }
    }

    public void unloadModel() {
        mWhisperEngine.deinitialize();
    }

    /**
     * Requests a thread count for upcoming transcriptions. Returns immediately; the change takes
     * effect when the next transcription starts, on the worker thread.
     */
    public void setNumThreads(int numThreads) {
        mRequestedThreads = numThreads;
    }

    public void setAction(Action action) {
        this.mAction = action;
    }

    public void setFilePath(String wavFile) {
        this.mWavFilePath = wavFile;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void transcribeFileLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                transcribeFile();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void transcribeFile() {
        try {
            if (mWhisperEngine.isInitialized() && mWavFilePath != null) {
                File waveFile = new File(mWavFilePath);
                if (waveFile.exists()) {
                    long startTime = System.currentTimeMillis();
                    sendUpdate(MSG_PROCESSING);

                    String result = null;
                    synchronized (mWhisperEngine) {
                        int requestedThreads = mRequestedThreads;
                        if (requestedThreads > 0) {
                            mWhisperEngine.setNumThreads(requestedThreads);
                        }
                        if (mAction == Action.TRANSCRIBE) {
                            result = mWhisperEngine.transcribeFile(mWavFilePath);
                        } else {
//                            result = mWhisperEngine.getTranslation(mWavFilePath);
                            Log.d(TAG, "TRANSLATE feature is not implemented");
                        }
                    }
                    sendResult(result);

                    long timeTaken = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                    sendUpdate(MSG_PROCESSING_DONE);
                } else {
                    sendUpdate(MSG_FILE_NOT_FOUND);
                }
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(message);
        }
    }
}
