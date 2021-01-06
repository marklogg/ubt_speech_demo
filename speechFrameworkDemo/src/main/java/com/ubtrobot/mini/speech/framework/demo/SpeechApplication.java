package com.ubtrobot.mini.speech.framework.demo;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechUtility;
import com.iflytek.cloud.msc.util.log.DebugLog;
import com.ubtech.utilcode.utils.thread.ThreadPool;
import com.ubtrobot.master.log.InfrequentLoggerFactory;
import com.ubtrobot.mini.speech.framework.AbstractSpeechApplication;
import com.ubtrobot.service.ServiceModules;
import com.ubtrobot.speech.SpeechService;
import com.ubtrobot.speech.SpeechSettings;
import com.ubtrobot.ulog.FwLoggerFactory2;
import com.ubtrobot.ulog.logger.android.AndroidLoggerFactory;

public class SpeechApplication extends AbstractSpeechApplication {
  @Override public void onCreate() {
    super.onCreate();
    StringBuffer param = new StringBuffer();
    param.append("appid=" + getString(R.string.app_id));
    param.append(",");
    param.append(SpeechConstant.ENGINE_MODE + "=" + SpeechConstant.MODE_MSC);
    SpeechUtility.createUtility(this, param.toString());
    DebugLog.setLogLevel(BuildConfig.DEBUG ? DebugLog.LOG_LEVEL.none : DebugLog.LOG_LEVEL.none);
    FwLoggerFactory2.setup(
        BuildConfig.DEBUG ? new AndroidLoggerFactory() : new InfrequentLoggerFactory());
    startService(new Intent(this, DemoMasterService.class));
    ServiceModules.declare(SpeechSettings.class,
        (aClass, moduleCreatedNotifier) -> moduleCreatedNotifier.notifyModuleCreated(
            DemoSpeech.INSTANCE.createSpeechSettings()));

    ServiceModules.declare(SpeechService.class,
        (aClass, moduleCreatedNotifier) -> ThreadPool.runOnNonUIThread(() -> {
          while (DemoSpeech.INSTANCE.createSpeechService() == null) {
            SystemClock.sleep(5);
          }
          Log.d("Logic", "Speech Service create ok..");
          moduleCreatedNotifier.notifyModuleCreated(DemoSpeech.INSTANCE.createSpeechService());
        }));
  }
}
