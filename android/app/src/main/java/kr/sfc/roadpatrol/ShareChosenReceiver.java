package kr.sfc.roadpatrol;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 공유창에서 사용자가 어떤 앱을 골랐는지 받는다. (Intent.createChooser의 IntentSender 콜백)
 * MainActivity가 공유 후 돌아왔을 때 카카오톡이었으면 글 붙여넣기 안내를 띄운다.
 */
public class ShareChosenReceiver extends BroadcastReceiver {

    static volatile String lastChosenPackage;

    @Override
    public void onReceive(Context context, Intent intent) {
        ComponentName chosen = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
        lastChosenPackage = chosen != null ? chosen.getPackageName() : null;
    }
}
