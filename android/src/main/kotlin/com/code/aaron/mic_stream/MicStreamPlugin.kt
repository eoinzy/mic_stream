package com.code.aaron.micstream

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar

/** MicStreamPlugin
 * In reference to flutters official sensors plugin
 * and the example of the streams_channel (v0.2.2) plugin
 */
@TargetApi(16) // Should be unnecessary, but isn't // fix build.gradle...?
class MicStreamPlugin : FlutterPlugin, StreamHandler, MethodCallHandler {
  /** New way of registering plugin */
  override fun onAttachedToEngine(binding: FlutterPluginBinding) {
    registerWith(binding.getBinaryMessenger())
  }

  /** Cleanup after connection loss to flutter */
  override fun onDetachedFromEngine(binding: FlutterPluginBinding?) {
    onCancel(null)
  }

  /** Deprecated way of registering plugin */
  fun registerWith(registrar: Registrar) {
    registerWith(registrar.messenger())
  }

  private fun registerWith(messenger: BinaryMessenger) {
    val microphone: EventChannel = EventChannel(messenger, MICROPHONE_CHANNEL_NAME)
    microphone.setStreamHandler(this)
    val methodChannel: MethodChannel = MethodChannel(messenger, MICROPHONE_METHOD_CHANNEL_NAME)
    methodChannel.setMethodCallHandler(this)
  }

  private var eventSink: EventChannel.EventSink? = null

  private var AUDIO_SOURCE: Int = MediaRecorder.AudioSource.DEFAULT
  private var SAMPLE_RATE: Int = 16000
  private var actualSampleRate: Int = 0
  private var CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO
  private var AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_8BIT
  private var actualBitDepth: Int = 0
  private var BUFFER_SIZE: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

  // Runnable management
  @kotlin.concurrent.Volatile
  private var record: Boolean = false

  @kotlin.concurrent.Volatile
  private var isRecording: Boolean = false

  // Method channel handlers to get sample rate / bit-depth
  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "getSampleRate" -> result.success(actualSampleRate.toDouble()) // cast to double just for compatibility with the iOS version
      "getBitDepth" -> result.success(this.actualBitDepth)
      "getBufferSize" -> result.success(this.BUFFER_SIZE)
      else -> result.notImplemented()
    }
  }

  private fun initRecorder() {
    // Try to initialize and start the recorder
    recorder = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)
    if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
      eventSink.error("-1", "PlatformError", null)
      return
    }

    recorder.startRecording()
  }

  private val runnable: java.lang.Runnable = object : java.lang.Runnable {
    override fun run() {
      if (recorder == null) initRecorder()
      isRecording = true

      actualSampleRate = recorder.getSampleRate()
      actualBitDepth = (if (recorder.getAudioFormat() == AudioFormat.ENCODING_PCM_8BIT) 8 else 16)

      // Wait until recorder is initialised
      while (recorder == null || recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING);

      // Repeatedly push audio samples to stream
      while (record) {
        // Read audio data into new byte array

        val data: ByteArray = ByteArray(BUFFER_SIZE)
        recorder.read(data, 0, BUFFER_SIZE)

        // push data into stream
        try {
          eventSink.success(data)
        } catch (e: java.lang.IllegalArgumentException) {
          println("mic_stream: " + data.contentHashCode() + " is not valid!")
          eventSink.error("-1", "Invalid Data", e)
        }
      }
      isRecording = false
    }
  }

  /** Bug fix by https://github.com/Lokhozt
   * following https://github.com/flutter/flutter/issues/34993 */
  private class MainThreadEventSink(eventSink: EventChannel.EventSink) : EventChannel.EventSink {
    private val eventSink: EventChannel.EventSink
    private val handler: android.os.Handler

    init {
      this.eventSink = eventSink
      handler = android.os.Handler(Looper.getMainLooper())
    }

    override fun success(o: Any?) {
      handler.post(object : java.lang.Runnable {
        override fun run() {
          eventSink.success(o)
        }
      })
    }

    override fun error(s: String?, s1: String?, o: Any?) {
      handler.post(object : java.lang.Runnable {
        override fun run() {
          eventSink.error(s, s1, o)
        }
      })
    }

    override fun endOfStream() {
      handler.post(object : java.lang.Runnable {
        override fun run() {
          eventSink.endOfStream()
        }
      })
    }
  }

  /** End */
  override fun onListen(args: Any, eventSink: EventChannel.EventSink) {
    if (isRecording) return

    val config: ArrayList<Int> = args as ArrayList<Int>

    // Set parameters, if available
    when (config.size) {
      4 -> {
        AUDIO_FORMAT = config.get(3)
        CHANNEL_CONFIG = config.get(2)
        SAMPLE_RATE = config.get(1)
        AUDIO_SOURCE = config.get(0)
        try {
          BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        } catch (e: java.lang.Exception) {
          eventSink.error("-3", "Invalid AudioRecord parameters", e)
        }
      }

      3 -> {
        CHANNEL_CONFIG = config.get(2)
        SAMPLE_RATE = config.get(1)
        AUDIO_SOURCE = config.get(0)
        try {
          BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        } catch (e: java.lang.Exception) {
          eventSink.error("-3", "Invalid AudioRecord parameters", e)
        }
      }

      2 -> {
        SAMPLE_RATE = config.get(1)
        AUDIO_SOURCE = config.get(0)
        try {
          BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        } catch (e: java.lang.Exception) {
          eventSink.error("-3", "Invalid AudioRecord parameters", e)
        }
      }

      1 -> {
        AUDIO_SOURCE = config.get(0)
        try {
          BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        } catch (e: java.lang.Exception) {
          eventSink.error("-3", "Invalid AudioRecord parameters", e)
        }
      }

      else -> try {
        BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
      } catch (e: java.lang.Exception) {
        eventSink.error("-3", "Invalid AudioRecord parameters", e)
      }
    }

    if (AUDIO_FORMAT != AudioFormat.ENCODING_PCM_8BIT && AUDIO_FORMAT != AudioFormat.ENCODING_PCM_16BIT) {
      eventSink.error("-3", "Invalid Audio Format specified", null)
      return
    }

    this.eventSink = MainThreadEventSink(eventSink)

    // Start runnable
    record = true
    java.lang.Thread(runnable).start()
  }

  override fun onCancel(o: Any?) {
    // Stop runnable
    record = false
    while (isRecording);
    if (recorder != null) {
      // Stop and reset audio recorder
      recorder.stop()
      recorder.release()
    }
    recorder = null
  }

  companion object {
    private const val MICROPHONE_CHANNEL_NAME: String = "aaron.code.com/mic_stream"
    private const val MICROPHONE_METHOD_CHANNEL_NAME: String = "aaron.code.com/mic_stream_method_channel"

    // Audio recorder + initial values
    @kotlin.concurrent.Volatile
    private var recorder: AudioRecord? = null
  }
}