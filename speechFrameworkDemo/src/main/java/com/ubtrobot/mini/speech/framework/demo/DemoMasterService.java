package com.ubtrobot.mini.speech.framework.demo;

import com.ubtechinc.mini.weinalib.wakeup.WeiNaWakeUpHelper;
import com.ubtrobot.master.service.MasterSystemService;

public class DemoMasterService extends MasterSystemService {
  @Override protected void onServiceCreate() {
    super.onServiceCreate();
    //If you want to use the WeiNa wake-up module(wakeup-5.0.0.aar), please call the initialization method first
    // The initialization of the wake-up module requires an available network
    WeiNaWakeUpHelper.get().initialize();

    //init speech modules
    DemoSpeech.INSTANCE.init(this);
  }

  @Override protected void onServiceDestroy() {
    super.onServiceDestroy();
  }
}
