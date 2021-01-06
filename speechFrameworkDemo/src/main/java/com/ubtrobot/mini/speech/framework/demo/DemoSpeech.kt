package com.ubtrobot.mini.speech.framework.demo

import android.os.Process
import android.text.TextUtils
import com.ubtech.utilcode.utils.LogUtils
import com.ubtech.utilcode.utils.Utils
import com.ubtech.utilcode.utils.notification.NotificationCenter
import com.ubtech.utilcode.utils.thread.ThreadPool
import com.ubtechinc.mini.weinalib.TencentVadRecorder
import com.ubtechinc.mini.weinalib.WeiNaMicApi
import com.ubtechinc.mini.weinalib.WeiNaRecorder
import com.ubtechinc.mini.weinalib.wakeup.WeiNaWakeUpDetector
import com.ubtrobot.action.ActionApi
import com.ubtrobot.async.ProgressivePromise
import com.ubtrobot.exception.CallExceptionTranslator2
import com.ubtrobot.master.adapter.ParcelableSessionCallAdapter2
import com.ubtrobot.master.competition.CompetingItem
import com.ubtrobot.master.param.ProtoParam
import com.ubtrobot.master.service.MasterSystemService
import com.ubtrobot.master.transport.message.parcel.ParcelableParam
import com.ubtrobot.mini.iflytek.wakeup.IflytekWakeUpDetector
import com.ubtrobot.mini.speech.framework.DingDangManager
import com.ubtrobot.mini.speech.framework.ResourceLoader
import com.ubtrobot.mini.speech.framework.ServiceConstants
import com.ubtrobot.mini.speech.framework.SpeechModuleFactory
import com.ubtrobot.mini.speech.framework.SpeechSettingStub
import com.ubtrobot.mini.speech.framework.WakeupAudioPlayer
import com.ubtrobot.mini.speech.framework.skill.SkillManager
import com.ubtrobot.mini.speech.framework.utils.MicApiHelper
import com.ubtrobot.mini.speech.framework.utils.ShakeHeadUtils
import com.ubtrobot.motor.MotorApi
import com.ubtrobot.parcelable.BaseProgress
import com.ubtrobot.speech.AbstractRecognizer
import com.ubtrobot.speech.AbstractSynthesizer
import com.ubtrobot.speech.AbstractUnderstander
import com.ubtrobot.speech.CompositeSpeechService
import com.ubtrobot.speech.RecognitionException
import com.ubtrobot.speech.RecognitionOption
import com.ubtrobot.speech.RecognitionProgress
import com.ubtrobot.speech.RecognitionResult
import com.ubtrobot.speech.RecognizerListener
import com.ubtrobot.speech.SpeechConstants
import com.ubtrobot.speech.SynthesisException
import com.ubtrobot.speech.SynthesisProgress
import com.ubtrobot.speech.SynthesizerListener
import com.ubtrobot.speech.UnderstanderListener
import com.ubtrobot.speech.UnderstandingException
import com.ubtrobot.speech.UnderstandingOption.Builder
import com.ubtrobot.speech.UnderstandingResult
import com.ubtrobot.speech.WakeUp
import com.ubtrobot.speech.parcelable.ASRState
import com.ubtrobot.speech.parcelable.AccessToken
import com.ubtrobot.speech.parcelable.InitResult
import com.ubtrobot.speech.parcelable.MicrophoneWakeupAngle
import com.ubtrobot.speech.parcelable.TTsState
import com.ubtrobot.speech.protos.Speech.WakeupParam
import com.ubtrobot.ulog.FwLoggerFactory2
import java.util.UUID

/**
 * <p>Created 06/03. </p>
 * <p>Copyright 2019 @feng.zhang</p>
 */

object DemoSpeech : SpeechModuleFactory() {
    private val LOGGER = FwLoggerFactory2.getLogger("Speech-Chain")
    private const val TAG = "SpeechFactory"

    private val appContext by lazy { Utils.getContext().applicationContext }

    //speechSettings
    private val speechSettingStub: SpeechSettingStub = SpeechSettingStub(
            Utils.getContext().applicationContext)

    //asr
    private var recognizer: AbstractRecognizer? = null

    //tts
    private var synthesizer: AbstractSynthesizer? = null

    //nlp
    private var understander: AbstractUnderstander? = null

    private var speechServiceStub: CompositeSpeechService? = null

    private var mRecognizerListener: RecognizerListener? = null
    private var mSynthesizerListener: SynthesizerListener? = null
    private var mUnderstanderListener: UnderstanderListener? = null

    private val mSkillManager by lazy { SkillManager() }

    //After positioning the sound source, move the head
    private fun shakeHead() {
        val lastLockAngel = MicApiHelper.getMicLockAngle()
        LOGGER.d("shakeHead ---lastLockAngel = $lastLockAngel")

        val callback = object : ShakeHeadUtils.MoveHeadCallback {

            override fun onProgress(moveAngel: Int, currmotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
            }

            override fun onSucc(moveAngel: Int, currMotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
                LOGGER.d("onSucc---" + "lastLockAngelxx = " + lastLockAngle +
                        ", moveAngel = " + moveAngel + ", finalMicAngle = " +
                        MicApiHelper.getMicLockAngle())
            }

            override fun onError(moveAngel: Int, currMotorAngel: Int) {
                val lastLockAngle = MicApiHelper.getMicLockAngle()
                val newMicAngle = (360 + lastLockAngle + moveAngel) % 360
                MicApiHelper.setMicLockAngle(newMicAngle.toShort(), true)
            }

        }
        ShakeHeadUtils.shakeHead(lastLockAngel, callback)

    }

    fun destroy() {
        WeiNaMicApi.get().release()
    }

    fun init(service: MasterSystemService?) {
        //Load the wake-up sound effect in advance
        WakeupAudioPlayer.get(appContext)

        val hostService = service!!

        //Here, monitor the wake-up angle of the microphone array
        WeiNaMicApi.get().addDoaAngleCallback { angle: Short ->

            //External applications may need to use the wake-up angle to publish it as an event
            hostService.publishCarefully(
                    ServiceConstants.PATH_MICROPHONE_ARRAY_WAKEUP_ANGLE,
                    ParcelableParam.create(
                            MicrophoneWakeupAngle(angle.toInt())))

            // act according to the angle, and turn the head to the sound source
            MicApiHelper.setMicLockAngle(angle, false)
            if (createSpeechSettings().isSpeechLinkable && ShakeHeadUtils.shakeHeadTiming == ShakeHeadUtils.ShakeHeadTiming.BeforeRecord) {
                if (!ActionApi.get().unsafeAction()) {
                    shakeHead()
                }
            }
        }

        mRecognizerListener = object : RecognizerListener {
            override fun onRecognizingFailure(p0: RecognitionException?) {
                val code = if (p0!!.extCode != 0) {
                    p0.extCode
                } else {
                    p0.code
                }
                //when asr recognizing failure, If you need to use the built-in expressiveness, you can publish the event
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                        ParcelableParam.create(ASRState(p0.message, code)))

                LogUtils.w(TAG,
                        "onRecognizingFailure:(code=" + p0.code + ", extCode = " + p0.extCode + ", msg=" + p0.message)
            }

            override fun onRecognizingResult(p0: RecognitionResult?) {
            }

            override fun onRecognizingProgress(p0: RecognitionProgress?) {
                p0?.let {
                    when (it.progress) {
                        //when asr recognizing begin, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_BEGAN -> {
                            hostService.publishCarefully(
                                    ServiceConstants.ACTION_SPEECH_ASR_STATE,
                                    ParcelableParam.create(ASRState(BaseProgress.PROGRESS_BEGAN)))
                        }
                        //when asr recognizing end, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_ENDED -> {
                            if (recognizer!!.isRecognizing) {//如果外部cancel掉了一次asr,则不需要发布PROGRESS_ENDED
                                hostService.publishCarefully(
                                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                                        ParcelableParam.create(
                                                ASRState(BaseProgress.PROGRESS_ENDED)))
                            }

                            // act according to the angle, and turn the head to the sound source
                            if (createSpeechSettings().isSpeechLinkable && ShakeHeadUtils.shakeHeadTiming == ShakeHeadUtils.ShakeHeadTiming.AfterRecord) {
                                if (!ActionApi.get().unsafeAction()) {
                                    shakeHead()
                                }
                            }

                            // stop recording skill
                            mSkillManager.stopSkill(
                                    SkillManager.SKILL_AUDIORECORD)

                        }
                    }
                }
            }
        }

        mSynthesizerListener = object : SynthesizerListener {
            override fun onSynthesizingProgress(p0: SynthesisProgress?) {
                p0?.let {
                    when (it.progress) {
                        //when tts synthesizing begin, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_BEGAN -> hostService.publishCarefully(
                                ServiceConstants.ACTION_SPEECH_TTS_STATE,
                                ParcelableParam.create(TTsState(
                                        BaseProgress.PROGRESS_BEGAN)))
                        //when tts synthesizing end, If you need to use the built-in expressiveness, you can publish the event
                        BaseProgress.PROGRESS_ENDED -> {
                            hostService.publishCarefully(
                                    ServiceConstants.ACTION_SPEECH_TTS_STATE,
                                    ParcelableParam.create(TTsState(
                                            BaseProgress.PROGRESS_ENDED)))
                            // stop Chat skill
                            mSkillManager.stopSkill(
                                    SkillManager.SKILL_CHAT)
                        }
                    }
                }
            }

            override fun onSynthesizingResult() {
            }

            override fun onSynthesizingFailure(p0: SynthesisException?) {
                LogUtils.w(TAG, "onSynthesizingFailure $p0")
                mSkillManager.stopSkill(SkillManager.SKILL_CHAT)
            }
        }

        mUnderstanderListener = object : UnderstanderListener {
            override fun onUnderstandingFailure(p0: UnderstandingException?) {
                val code = if (p0!!.extCode != 0) {
                    p0.extCode
                } else {
                    p0.code
                }

                //when nlp failure , If you need to use the built-in expressiveness, you can publish the event
                if (code == 403) {
                    hostService.publishCarefully(
                            ServiceConstants.ACTION_SPEECH_ASR_STATE,
                            ParcelableParam.create(
                                    ASRState(p0.message, ASRState.CODE_UNAUTHENTICATED)))
                } else {
                    hostService.publishCarefully(
                            ServiceConstants.ACTION_SPEECH_ASR_STATE,
                            ParcelableParam.create(ASRState(p0.message, p0.code)))
                }
                LogUtils.w(TAG,
                        "onUnderstandingFailure:(code=" + p0.code + ", extCode = " + p0.extCode + ", msg=" + p0.message)
            }

            override fun onUnderstandingResult(p0: UnderstandingResult?) {
                //when nlp completed, If you need to use the built-in expressiveness, you can publish the event
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_ASR_STATE,
                        ParcelableParam.create(ASRState("recognized")))
            }
        }

        //Use WeiNa wake-up module in SDK, Awakening word is "Hey, Mini"
        //If you want to use the WeiNa wake-up module, please call the initialization method first
        val wakeUpDetector = WeiNaWakeUpDetector(WeiNaRecorder(false))
        wakeUpDetector.registerListener { wakeUp ->
            handleWakeup(hostService, wakeUp, service)
        }

        val iflytekWakeUpDetector = IflytekWakeUpDetector(WeiNaRecorder(false), "5fe4288e")
        iflytekWakeUpDetector.registerListener { wakeUp ->
            handleWakeup(hostService, wakeUp, service)
        }

        //init DingDang
        DingDangManager.load(appContext) { success ->
            if (success) {
                //This class is similar to android AudioRecorder api, but with Vad detection.
                val asrRecorder = TencentVadRecorder(ResourceLoader.vad_path)

                //your Recognizer
                recognizer = DemoRecognizer(
                        asrRecorder)

                recognizer!!.registerListener(mRecognizerListener)

                //your Synthesizer
                synthesizer = DemoSynthesizer()

                synthesizer!!.registerListener(mSynthesizerListener)

                //your Understander(nlp)
                understander = DemoUnderstander()

                understander!!.registerListener(mUnderstanderListener)

                speechServiceStub = CompositeSpeechService.Builder().setRecognizer(recognizer)
                        .setSynthesizer(synthesizer)
                        .setUnderstander(understander)
                        .setWakeUpDetector(wakeUpDetector)
                        .build()

                //The Speech Service has been started. Publish the event and notify built-in apps
                hostService.publishCarefully(
                        ServiceConstants.ACTION_SPEECH_INIT_RESULT,
                        ParcelableParam.create(InitResult(0)))

                //if (!TextUtils.isEmpty(
                //                PreferenceManager.getDefaultSharedPreferences(appContext).getString(
                //                        KEY_NLP_CODE, null))) {
                //    hostService.publishCarefully(
                //            ServiceConstants.ACTION_SPEECH_TOKEN_STATE,
                //            ParcelableParam.create(TokenState(0, "success")))
                //}

                //The Speech Service has been started. Publish the event
                NotificationCenter.defaultCenter().publish(
                        ServiceConstants.PATH_MICROPHONE_ARRAY_INIT_RESULT, this)
                LogUtils.i("init success.")
            } else {
                LogUtils.e(
                        "Initialization configuration of wake-up module failed, restart application...")
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun handleWakeup(hostService: MasterSystemService,
            wakeUp: WakeUp?,
            service: MasterSystemService) {
        LOGGER.w("publish wakeup.")
        //This wake-up event must be published and will be used by other built-in applications
        hostService.publishCarefully(
                ServiceConstants.ACTION_SPEECH_WAKEUP,
                ProtoParam.create(
                        WakeupParam.newBuilder().build()))
        //After waking up, if you want to use the built-in application to start the voice recognition link,
        // you can post this event, and if you do not want to use this way to start the voice link, you can not publish it.
        hostService.publishCarefully(SpeechConstants.ACTION_WAKE_UP,
                ParcelableParam.create(wakeUp))

        val o: RecognitionOption = RecognitionOption.Builder(
                RecognitionOption.MODE_SINGLE).setUnderstandingOption(
                Builder().setSessionId(
                        UUID.randomUUID().toString()).build()).build()
        val callAdapter = ParcelableSessionCallAdapter2(
                service, "speech",
                service.openCompetitionSession().addCompeting {
                    listOf(CompetingItem("speech", "recognizer"))
                })
        val promise: ProgressivePromise<RecognitionResult, RecognitionException, RecognitionProgress> = callAdapter.callStickily(
                "/speech/recognize", o,
                RecognitionResult::class.java,
                RecognitionProgress::class.java
        ) { e ->
            RecognitionException(
                    CallExceptionTranslator2.translate(e), e.subCode,
                    e.message)
        }
        promise.done {
            //处理成功结果
        }
        promise.fail {
            //处理失败结果
        }
        promise.progress {
            //处理中间结果
        }

        //play a sound effect
        WakeupAudioPlayer.get(appContext).play()
        //clear motor protected flag
        ThreadPool.runOnNonUIThread {
            MotorApi.get().clearProtectFlag(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                    14)
        }
    }

    override fun createSpeechService(): CompositeSpeechService? {
        return speechServiceStub
    }

    override fun createSpeechSettings(): SpeechSettingStub {
        return speechSettingStub
    }

    override fun refreshUnderstanderCode(token: AccessToken?, callback: Callback?) {
        if (!TextUtils.isEmpty(token!!.code) && !TextUtils.isEmpty(token.codeVerifier)) {
            LOGGER.i("refresh Code: $token.code, codeVerifier:$token.codeVerifier")
            //todo
        }
    }
}

