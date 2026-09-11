package com.morningmission.app;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private MorningView morningView;
    private SharedPreferences prefs;
    private DevicePolicyManager dpm;
    private ComponentName admin;
    private boolean kidLocked = false;
    private MediaPlayer victoryPlayer;

    private static final String[] PRESET_TASKS={"Wake Up","Use Bathroom","Get Dressed","Breakfast","Brush Teeth","Brush Hair","Wash Face","Shoes On","Backpack","Jacket","Feed Pet","Vitamins"};
    private static final String[] PRESET_KEYS={"WAKE","BATH","DRESS","EAT","BRUSH","HAIR","WASH","SHOES","PACK","JACKET","PET","VITAMIN"};

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=getSharedPreferences("morning_mission",MODE_PRIVATE);
        ensureDefaults();
        dpm=(DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
        admin=new ComponentName(this,MorningDeviceAdminReceiver.class);
        morningView=new MorningView(this);
        setContentView(morningView);
        morningView.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                morningView.setSystemInsets(bars.top,bars.bottom);
            } else {
                morningView.setSystemInsets(insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        if(!PinSecurity.hasPin(prefs)) new Handler(Looper.getMainLooper()).postDelayed(this::showCreatePin,350);
    }

    private void ensureDefaults(){
        if(!prefs.contains("minutes")) prefs.edit().putInt("minutes",15).apply();
        if(!prefs.contains("buddy")) prefs.edit().putInt("buddy",0).apply();
        if(!prefs.contains("task_names")) saveTasks(
                new String[]{"Get Dressed","Breakfast","Brush Teeth","Shoes On","Backpack"},
                new String[]{"DRESS","EAT","BRUSH","SHOES","PACK"});
    }

    @Override public void onBackPressed(){ if(kidLocked) showUnlockPin(); else super.onBackPressed(); }

    private void setKidImmersive(boolean enabled){
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController c=getWindow().getInsetsController();
            if(c!=null){ if(enabled)c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars()); else c.show(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars()); }
        }else getWindow().getDecorView().setSystemUiVisibility(enabled ?
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void startKidMode(){
        if(!PinSecurity.hasPin(prefs)){showCreatePin();return;}
        morningView.startRoutine();
        try{
            if(dpm.isDeviceOwnerApp(getPackageName())){
                dpm.setLockTaskPackages(admin,new String[]{getPackageName()});
                if(Build.VERSION.SDK_INT>=28)dpm.setLockTaskFeatures(admin,DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
            startLockTask(); kidLocked=true; setKidImmersive(true); morningView.invalidate();
            Toast.makeText(this,dpm.isDeviceOwnerApp(getPackageName())?"Full Kid Lock is on":"Kid Mode started",Toast.LENGTH_SHORT).show();
        }catch(Exception e){Toast.makeText(this,"Routine started. Full lock is not provisioned yet.",Toast.LENGTH_LONG).show();}
    }
    private void stopKidMode(){try{stopLockTask();}catch(Exception ignored){} kidLocked=false;setKidImmersive(false);morningView.invalidate();}

    private void showCreatePin(){
        LinearLayout box=dialogBox(); box.addView(label("Create a 4–6 digit Grown-Ups PIN. It protects settings and Parent Unlock.",16));
        EditText p1=pinField("New PIN"),p2=pinField("Confirm PIN");box.addView(p1);box.addView(p2);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Set Grown-Ups PIN").setView(box).setPositiveButton("Save",null).setNegativeButton(PinSecurity.hasPin(prefs)?"Cancel":"",null).create();
        d.setCancelable(PinSecurity.hasPin(prefs)); d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String a=p1.getText().toString(),c=p2.getText().toString(); if(!a.matches("\\d{4,6}")){p1.setError("Use 4–6 numbers");return;} if(!a.equals(c)){p2.setError("PINs do not match");return;} PinSecurity.savePin(prefs,a);d.dismiss();
        }));d.show();
    }
    private void showUnlockPin(){askForPin("Parent Unlock","Enter the Grown-Ups PIN to leave Morning Mission.",this::stopKidMode);}
    private void showSettingsProtected(){if(!PinSecurity.hasPin(prefs)){showCreatePin();return;}askForPin("Grown-Ups","Enter your PIN to open settings.",this::showSettings);}
    private void askForPin(String title,String message,Runnable success){
        LinearLayout box=dialogBox();box.addView(label(message,16));EditText pin=pinField("PIN");box.addView(pin);
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(PinSecurity.verify(prefs,pin.getText().toString())){d.dismiss();success.run();}else{pin.setError("Incorrect PIN");pin.setText("");}}));d.show();
    }

    private void showSettings(){
        LinearLayout box=dialogBox(); boolean owner=dpm.isDeviceOwnerApp(getPackageName());
        box.addView(label(owner?"🔒 Full Kid Lock available":"🛡 Standard protection",16));
        Button routine=button("Edit Morning Routine");routine.setOnClickListener(v->showTaskEditor());
        Button buddy=button("Choose Buddy");buddy.setOnClickListener(v->{morningView.openBuddyPicker();});
        Button time=button("Set default timer: "+prefs.getInt("minutes",15)+" min");time.setOnClickListener(v->showCustomTimeDialog());
        CheckBox song=check("Victory song",prefs.getBoolean("song",true),"song");CheckBox dance=check("Buddy victory dance",prefs.getBoolean("dance",true),"dance");CheckBox confetti=check("Confetti celebration",prefs.getBoolean("confetti",true),"confetti");
        Button pin=button("Change Grown-Ups PIN");pin.setOnClickListener(v->showCreatePin());Button reset=button("Reset today’s routine");reset.setOnClickListener(v->morningView.resetRoutine());
        box.addView(routine);box.addView(buddy);box.addView(time);box.addView(song);box.addView(dance);box.addView(confetti);box.addView(pin);box.addView(reset);
        new AlertDialog.Builder(this).setTitle("Grown-Ups Settings").setView(box).setPositiveButton("Done",null).show();
    }
    private CheckBox check(String text,boolean value,String key){CheckBox c=new CheckBox(this);c.setText(text);c.setChecked(value);c.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(key,v).apply());return c;}

    void showCustomTimeDialog(){ morningView.openTimePicker(); }

    private void showTaskEditor(){
        ArrayList<String> names=new ArrayList<>(Arrays.asList(loadTaskNames()));ArrayList<String> keys=new ArrayList<>(Arrays.asList(loadTaskKeys()));
        LinearLayout outer=dialogBox();TextView help=label("Tap a task to rename it or choose a different activity picture. Long editing is kept in Grown-Ups so kids can’t change the routine.",14);outer.addView(help);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);outer.addView(list);
        final Runnable[] rebuild=new Runnable[1];rebuild[0]=()->{list.removeAllViews();for(int i=0;i<names.size();i++){final int idx=i;Button row=button(activityEmoji(keys.get(i))+"   "+names.get(i)+"   ✎");row.setOnClickListener(v->showEditTask(names,keys,idx,rebuild[0]));list.addView(row);} Button add=button("＋ Add a Task");add.setOnClickListener(v->showAddPreset(names,keys,rebuild[0]));list.addView(add);};rebuild[0].run();
        new AlertDialog.Builder(this).setTitle("Edit Morning Routine").setView(outer).setPositiveButton("Save",(d,w)->{saveTasks(names.toArray(new String[0]),keys.toArray(new String[0]));morningView.reloadTasks();}).setNegativeButton("Cancel",null).show();
    }
    private void showEditTask(ArrayList<String> names,ArrayList<String> keys,int idx,Runnable refresh){
        LinearLayout box=dialogBox();EditText name=new EditText(this);name.setText(names.get(idx));box.addView(name);Spinner sp=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,PRESET_TASKS);sp.setAdapter(a);int pos=indexOf(PRESET_KEYS,keys.get(idx));sp.setSelection(Math.max(0,pos));box.addView(sp);
        new AlertDialog.Builder(this).setTitle("Edit Task").setView(box).setPositiveButton("Save",(d,w)->{names.set(idx,name.getText().toString().trim().isEmpty()?PRESET_TASKS[sp.getSelectedItemPosition()]:name.getText().toString().trim());keys.set(idx,PRESET_KEYS[sp.getSelectedItemPosition()]);refresh.run();}).setNeutralButton("Remove",(d,w)->{names.remove(idx);keys.remove(idx);refresh.run();}).setNegativeButton("Cancel",null).show();
    }
    private void showAddPreset(ArrayList<String> names,ArrayList<String> keys,Runnable refresh){
        new AlertDialog.Builder(this).setTitle("Choose a Task Preset").setItems(PRESET_TASKS,(d,which)->{names.add(PRESET_TASKS[which]);keys.add(PRESET_KEYS[which]);refresh.run();}).setNegativeButton("Cancel",null).show();
    }
    private int indexOf(String[] a,String s){for(int i=0;i<a.length;i++)if(a[i].equals(s))return i;return 0;}
    private String activityEmoji(String key){return switch(key){case"WAKE"->"☀";case"BATH"->"🚽";case"DRESS"->"👕";case"EAT"->"🥣";case"BRUSH"->"🪥";case"HAIR"->"🪮";case"WASH"->"💦";case"SHOES"->"👟";case"PACK"->"🎒";case"JACKET"->"🧥";case"PET"->"🐾";case"VITAMIN"->"⭐";default->"✓";};}

    private String[] loadTaskNames(){return prefs.getString("task_names","Get Dressed|Breakfast|Brush Teeth|Shoes On|Backpack").split("\\|",-1);}
    private String[] loadTaskKeys(){return prefs.getString("task_keys","DRESS|EAT|BRUSH|SHOES|PACK").split("\\|",-1);}
    private void saveTasks(String[] names,String[] keys){prefs.edit().putString("task_names",String.join("|",names)).putString("task_keys",String.join("|",keys)).apply();}

    private LinearLayout dialogBox(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);int p=dp(20);l.setPadding(p,p/2,p,p/2);return l;}
    private TextView label(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.rgb(38,54,94));t.setPadding(0,dp(6),0,dp(10));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);return b;}
    private EditText pinField(String h){EditText e=new EditText(this);e.setHint(h);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setMaxEms(6);return e;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    void playVictory(){if(!prefs.getBoolean("song",true))return;try{if(victoryPlayer!=null)victoryPlayer.release();victoryPlayer=MediaPlayer.create(this,R.raw.victory);victoryPlayer.start();}catch(Exception ignored){}}
    void playBuddySound(int buddy){
        final int[] sounds={R.raw.buddy_burger_sound,R.raw.buddy_bee_sound,R.raw.buddy_pug_sound,R.raw.buddy_shark_sound,R.raw.buddy_dino_sound,R.raw.buddy_cloud_sound,R.raw.buddy_kitty_sound};
        try{
            int idx=Math.max(0,Math.min(buddy,sounds.length-1));
            MediaPlayer mp=MediaPlayer.create(this,sounds[idx]);
            if(mp!=null){mp.setVolume(.72f,.72f);mp.setOnCompletionListener(MediaPlayer::release);mp.start();}
        }catch(Exception ignored){}
    }

    private class MorningView extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),stroke=new Paint(Paint.ANTI_ALIAS_FLAG);private final Handler h=new Handler(Looper.getMainLooper());
        private String[] tasks,taskKeys;private boolean[] done;private int active=0;private boolean running=false,celebrated=false,zeroAlerted=false,buddyPicker=false,timePicker=false;private int tempMinutes=15;private long endAt=0,cheerUntil=0,frame=0,completionRemainingMs=-1;private float scale=1f,logicalH=1920;private int insetTopPx=0,insetBottomPx=0;
        private Bitmap[] buddyArt;
        private Bitmap homeBg;
        private Bitmap[] adventureBg;
        private final String[] buddyNames={"Burger Buddy","Queen Bee","Pug Pal","Splash Buddy","Sprout Dino","Cloud Pup","Sweet Kitty"};
        private final int[] minuteValues={1,2,5,10,15,30,60};
        private final Runnable tick=new Runnable(){public void run(){frame=SystemClock.uptimeMillis();if(running&&remainingMs()<=0&&!allDone()&&!zeroAlerted){zeroAlerted=true;playBuddySound(prefs.getInt("buddy",0));}invalidate();h.postDelayed(this,33);}};
        MorningView(Context c){
            super(c);stroke.setStrokeCap(Paint.Cap.ROUND);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            int[] ids={R.drawable.buddy_burger,R.drawable.buddy_bee,R.drawable.buddy_pug,R.drawable.buddy_shark,R.drawable.buddy_dino,R.drawable.buddy_cloud,R.drawable.buddy_kitty};
            buddyArt=new Bitmap[ids.length];for(int i=0;i<ids.length;i++)buddyArt[i]=BitmapFactory.decodeResource(getResources(),ids[i]);
            homeBg=BitmapFactory.decodeResource(getResources(),R.drawable.bg_home_storybook);
            int[] bgIds={R.drawable.bg_adventure_burger,R.drawable.bg_adventure_bee,R.drawable.bg_adventure_pug,R.drawable.bg_adventure_shark,R.drawable.bg_adventure_dino,R.drawable.bg_adventure_cloud,R.drawable.bg_adventure_kitty};
            adventureBg=new Bitmap[bgIds.length];for(int i=0;i<bgIds.length;i++)adventureBg[i]=BitmapFactory.decodeResource(getResources(),bgIds[i]);
            reloadTasks();h.post(tick);
        }
        void setSystemInsets(int top,int bottom){insetTopPx=top;insetBottomPx=bottom;invalidate();}
        float topSafe(){return insetTopPx/Math.max(scale,.01f)+18;}float bottomSafe(){return insetBottomPx/Math.max(scale,.01f)+18;}
        void reloadTasks(){tasks=loadTaskNames();taskKeys=loadTaskKeys();if(taskKeys.length!=tasks.length){taskKeys=new String[tasks.length];Arrays.fill(taskKeys,"DRESS");}done=new boolean[tasks.length];active=0;running=false;celebrated=false;zeroAlerted=false;endAt=0;completionRemainingMs=-1;invalidate();}
        void openBuddyPicker(){buddyPicker=true;timePicker=false;invalidate();}
        void openTimePicker(){tempMinutes=prefs.getInt("minutes",15);timePicker=true;buddyPicker=false;invalidate();}
        void startRoutine(){if(!running){running=true;endAt=SystemClock.elapsedRealtime()+prefs.getInt("minutes",15)*60_000L;}}
        void resetRoutine(){Arrays.fill(done,false);active=0;running=false;celebrated=false;zeroAlerted=false;endAt=0;cheerUntil=0;completionRemainingMs=-1;if(victoryPlayer!=null&&victoryPlayer.isPlaying())victoryPlayer.stop();invalidate();}
        long remainingMs(){if(allDone()&&completionRemainingMs>=0)return completionRemainingMs;return running?Math.max(0,endAt-SystemClock.elapsedRealtime()):prefs.getInt("minutes",15)*60_000L;}
        boolean allDone(){if(done.length==0)return false;for(boolean b:done)if(!b)return false;return true;}

        @Override protected void onDraw(Canvas c){super.onDraw(c);scale=getWidth()/1080f;logicalH=getHeight()/scale;c.save();c.scale(scale,scale);drawScene(c);c.restore();}
        private void drawScene(Canvas c){
            float ts=topSafe();
            if(running){drawActiveAdventure(c,ts);if(allDone())drawCelebration(c);return;}
            if(homeBg!=null){p.setAlpha(255);c.drawBitmap(homeBg,null,new RectF(0,0,1080,logicalH),p);}else{LinearGradient g=new LinearGradient(0,0,0,logicalH,Color.rgb(220,246,255),Color.rgb(244,255,235),Shader.TileMode.CLAMP);p.setShader(g);c.drawRect(0,0,1080,logicalH,p);p.setShader(null);}
            drawHeader(c,ts);drawBuddy(c,prefs.getInt("buddy",0),230,ts+330,currentState(),1.08f);drawMinuteBubbles(c,ts);drawTimer(c,ts);drawTasks(c,ts);drawBottom(c);if(buddyPicker)drawBuddyPicker(c);if(timePicker)drawTimePicker(c);
        }
        private void drawSetupLandscape(Canvas c,float ts){
            // Decorative storybook scenery behind the real interactive controls.
            p.setColor(0xffffdc58);c.drawCircle(945,ts+225,72,p);p.setColor(0xffffed8a);c.drawCircle(945,ts+225,50,p);
            for(int i=0;i<8;i++){double a=i*Math.PI/4;stroke.setColor(0xffffcf45);stroke.setStrokeWidth(8);c.drawLine(945+(float)Math.cos(a)*82,ts+225+(float)Math.sin(a)*82,945+(float)Math.cos(a)*106,ts+225+(float)Math.sin(a)*106,stroke);}
            p.setColor(0x99ffffff);c.drawCircle(125,ts+165,42,p);c.drawCircle(170,ts+150,58,p);c.drawCircle(222,ts+170,40,p);
            float hillY=Math.min(logicalH-bottomSafe()-105,ts+1180);p.setColor(0xffbde990);c.drawOval(-180,hillY-150,650,hillY+130,p);p.setColor(0xff9ddd71);c.drawOval(350,hillY-130,1260,hillY+150,p);
            for(int i=0;i<9;i++){float x=20+i*135;float y=hillY-35-(i%3)*18;p.setColor(0xff4fb96d);c.drawCircle(x,y,34,p);p.setColor(0xff3a9c5d);c.drawRect(x-7,y+22,x+7,y+70,p);}
        }

        private void drawActiveAdventure(Canvas c,float ts){
            int buddy=prefs.getInt("buddy",0);float bottom=logicalH-bottomSafe();
            // v0.6: the adventure IS the screen — no giant white container around it.
            drawAdventureBackdropFull(c,buddy,ts,bottom);
            // Gentle bright overlay near the top keeps controls readable without hiding the illustrated world.
            LinearGradient fade=new LinearGradient(0,ts,0,ts+360,0x660083d0,0x000083d0,Shader.TileMode.CLAMP);p.setShader(fade);c.drawRect(0,ts,1080,ts+390,p);p.setShader(null);

            shadowCard(c,28,ts+22,140,ts+105,40,0xeaffffff);text(c,"‹",84,ts+79,58,0xff1d5cab,Paint.Align.CENTER,true);
            bubbleText(c,"Buddy",540,ts+78,55,0xffffffff);bubbleText(c,"Adventure!",540,ts+137,61,0xfffff06a);
            shadowCard(c,930,ts+24,1048,ts+104,40,0xeaffffff);text(c,"Ⅱ",989,ts+77,31,0xff225ca9,Paint.Align.CENTER,true);

            // Large floating clock like the approved mockup.
            shadowCard(c,225,ts+155,855,ts+315,50,0xf8ffffff);
            long sec=(remainingMs()+999)/1000;String time=String.format(Locale.US,"%d:%02d",sec/60,sec%60);
            text(c,time,540,ts+253,82,0xff153a72,Paint.Align.CENTER,true);
            String clockCaption=allDone()?(completionRemainingMs>0?"FINISHED WITH TIME LEFT!":"MISSION COMPLETE!"):(remainingMs()==0?"TIME IS UP — FINISH YOUR TASKS!":"Keep going!");
            text(c,clockCaption,540,ts+292,21,allDone()?0xff24864e:0xff567398,Paint.Align.CENTER,true);

            // Adventure world occupies almost the entire middle of the phone.
            float sceneTop=ts+330;float taskTop=bottom-325;float sceneBottom=taskTop-18;
            int totalMin=Math.max(1,prefs.getInt("minutes",15));int count=Math.max(1,Math.min(totalMin,12));long totalMs=totalMin*60_000L;
            float progress=allDone()?1f:Math.min(1f,Math.max(0f,(totalMs-remainingMs())/(float)totalMs));int eaten=allDone()?count:Math.min(count,(int)Math.floor(progress*count));

            // A translucent collectible ribbon across the top, as in the concept.
            shadowCard(c,75,sceneTop+18,1005,sceneTop+125,48,0xd8ffffff);
            float gap=(1005-130f)/Math.max(1,count);for(int i=0;i<count;i++){
                float x=125+i*gap+gap*.5f;float size=count<=7?34:(count<=10?29:24);drawCollectible(c,buddy,x,sceneTop+72,size,i<eaten);
            }

            // BIG animated buddy in the center of the illustrated environment.
            float buddyX=430+(float)Math.sin(frame/650.0)*25f;float buddyY=sceneTop+(sceneBottom-sceneTop)*.57f;
            float within=(progress*count)-(float)Math.floor(progress*count);boolean munch=!allDone()&&eaten>0&&within<.12f;
            String state=munch?"CHEER":currentState();float buddyScale=allDone()?.94f:.88f;
            drawBuddy(c,buddy,buddyX,buddyY,state,buddyScale);
            if(munch){shadowCard(c,buddyX+80,buddyY-220,buddyX+245,buddyY-145,35,0xf7ffffff);text(c,buddy==1?"BUZZ!":buddy==3?"CHOMP!":"YUM!",buddyX+162,buddyY-173,24,0xff315d9b,Paint.Align.CENTER,true);}

            // Large, obvious destination at lower right.
            drawGoal(c,buddy,885,sceneBottom-120,allDone());
            if(!allDone()){text(c,"GOAL",885,sceneBottom-18,18,0xffffffff,Paint.Align.CENTER,true);}

            // Light progress strip embedded into the world instead of a white panel.
            float progL=115,progR=820,progY=sceneBottom-52;rounded(c,progL,progY,progR,progY+22,11,0x88ffffff);rounded(c,progL,progY,progL+(progR-progL)*progress,progY+22,11,0xff55ce85);
            text(c,eaten+" of "+count+" "+collectiblePlural(buddy)+" collected",115,progY-13,19,0xffffffff,Paint.Align.LEFT,true);

            // Floating task card, closely matching the approved phone mockup.
            shadowCard(c,45,taskTop,1035,taskTop+125,32,allDone()?0xffe7f8e9:0xf8ffffff);
            if(allDone()){
                text(c,"★  MORNING MISSION COMPLETE!",540,taskTop+52,31,0xff247c48,Paint.Align.CENTER,true);
                String finish=completionRemainingMs>0?"You finished with "+formatShortTime(completionRemainingMs)+" left!":"You made it to the goal!";
                text(c,finish,540,taskTop+91,23,0xff527563,Paint.Align.CENTER,true);
            }else{
                drawActivityThumbnail(c,buddy,taskKeys[Math.min(active,taskKeys.length-1)],115,taskTop+63,.30f);
                text(c,"Current Task",190,taskTop+35,18,0xff47729a,Paint.Align.LEFT,true);
                text(c,tasks[Math.min(active,tasks.length-1)],190,taskTop+73,30,0xff17345f,Paint.Align.LEFT,true);
                text(c,(active+1)+" of "+tasks.length,950,taskTop+67,22,0xff33845a,Paint.Align.RIGHT,true);
            }
            float buttonTop=bottom-175;shadowCard(c,85,buttonTop,995,bottom-25,58,allDone()?0xff42bf69:0xff30c764);
            text(c,allDone()?"★  YOU DID IT!  ★":"✓  I DID IT! — NEXT TASK",540,buttonTop+91,33,Color.WHITE,Paint.Align.CENTER,true);
        }

        private String formatShortTime(long ms){long s=Math.max(0,(ms+999)/1000);return String.format(Locale.US,"%d:%02d",s/60,s%60);}
        private String collectiblePlural(int buddy){return new String[]{"mini burgers","honey drops","pup treats","fish","leaves","stars","fish treats"}[buddy];}

        private PointF adventurePoint(float index,int count,float left,float top,float right,float bottom){
            int cols=Math.min(15,count);int rows=(int)Math.ceil(count/(float)cols);int row=Math.min(rows-1,(int)(index/cols));float col=index-row*cols;int itemsThis=Math.min(cols,count-row*cols);float gap=itemsThis<=1?0:(right-left)/(itemsThis-1f);boolean reverse=(row%2)==1;float x=itemsThis<=1?(left+right)/2:(reverse?right-col*gap:left+col*gap);float y=rows<=1?(top+bottom)/2:top+row*(bottom-top)/Math.max(1,rows-1);return new PointF(x,y);
        }

        private void drawAdventureBackdropFull(Canvas c,int buddy,float ts,float bottom){
            Bitmap bg=adventureBg[Math.max(0,Math.min(buddy,adventureBg.length-1))];
            if(bg!=null){p.setAlpha(255);c.drawBitmap(bg,null,new RectF(0,ts,1080,bottom),p);p.setColor(0x18003d77);c.drawRect(0,ts,1080,bottom,p);}
            else{LinearGradient g=new LinearGradient(0,ts,0,bottom,0xff8be2f2,0xff36bcd3,Shader.TileMode.CLAMP);p.setShader(g);c.drawRect(0,ts,1080,bottom,p);p.setShader(null);}
        }
        private void drawAdventureWorld(Canvas c,int buddy,float l,float t,float r,float b){
            c.save();c.clipRect(l,t,r,b);
            Bitmap bg=adventureBg[Math.max(0,Math.min(buddy,adventureBg.length-1))];
            if(bg!=null)c.drawBitmap(bg,null,new RectF(l,t,r,b),p);
            p.setColor(0x12ffffff);c.drawRect(l,t,r,b,p);
            if(buddy==3){p.setColor(0x50ffffff);for(int i=0;i<18;i++)c.drawCircle(l+((i*113)%900),t+40+((i*71)%(int)Math.max(80,b-t-160)),6+(i%3)*4,p);}
            c.restore();
        }
        private void drawFlower(Canvas c,float x,float y,int variant){p.setColor(new int[]{0xffff7298,0xffffcc58,0xff9f7cff,0xffffffff}[variant]);for(int k=0;k<5;k++){double a=k*Math.PI*2/5;c.drawCircle(x+(float)Math.cos(a)*15,y+(float)Math.sin(a)*15,10,p);}p.setColor(0xffffb83e);c.drawCircle(x,y,8,p);stroke.setColor(0xff3d9a57);stroke.setStrokeWidth(5);c.drawLine(x,y+18,x,y+48,stroke);}
        private void drawCoral(Canvas c,float x,float y,int variant){stroke.setStrokeWidth(9);stroke.setColor(new int[]{0xffff718b,0xff9d77e9,0xffffa54e}[variant]);c.drawLine(x,y,x,y-52,stroke);c.drawLine(x,y-28,x-20,y-48,stroke);c.drawLine(x,y-35,x+20,y-65,stroke);}

        private void drawCollectible(Canvas c,int buddy,float x,float y,float s,boolean eaten){
            c.save();c.translate(x,y);if(eaten){p.setAlpha(70);}else p.setAlpha(255);
            switch(buddy){
                case 0 -> {p.setColor(0xffffc55b);c.drawOval(-s,-s*.55f,s,s*.05f,p);p.setColor(0xff64b85c);c.drawRect(-s*.85f,-s*.02f,s*.85f,s*.22f,p);p.setColor(0xff8b4b2d);c.drawRect(-s*.72f,s*.20f,s*.72f,s*.48f,p);p.setColor(0xffffd36c);c.drawOval(-s,s*.35f,s,s*.78f,p);}
                case 1 -> {p.setColor(0xffffc945);Path d=new Path();d.moveTo(0,-s);d.quadTo(s*.9f,0,0,s);d.quadTo(-s*.9f,0,0,-s);c.drawPath(d,p);}
                case 2 -> {p.setColor(0xfffff5dc);stroke.setColor(0xffd7b37b);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(3);c.drawRoundRect(-s*.65f,-s*.25f,s*.65f,s*.25f,s*.2f,s*.2f,p);p.setStyle(Paint.Style.FILL);c.drawCircle(-s*.75f,-s*.25f,s*.32f,p);c.drawCircle(-s*.75f,s*.25f,s*.32f,p);c.drawCircle(s*.75f,-s*.25f,s*.32f,p);c.drawCircle(s*.75f,s*.25f,s*.32f,p);}
                case 3,6 -> {p.setColor(buddy==3?0xffff8a32:0xffff75a5);c.drawOval(-s*.8f,-s*.45f,s*.6f,s*.45f,p);Path tail=new Path();tail.moveTo(s*.5f,0);tail.lineTo(s,-s*.55f);tail.lineTo(s,s*.55f);tail.close();c.drawPath(tail,p);p.setColor(Color.WHITE);c.drawCircle(-s*.42f,-s*.1f,s*.13f,p);p.setColor(0xff263554);c.drawCircle(-s*.42f,-s*.1f,s*.055f,p);}
                case 4 -> {p.setColor(0xff55bb68);c.save();c.rotate(-25);c.drawOval(-s*.75f,-s*.38f,s*.75f,s*.38f,p);stroke.setColor(0xff319350);stroke.setStrokeWidth(3);c.drawLine(-s*.55f,0,s*.65f,0,stroke);c.restore();}
                case 5 -> {drawStar(c,0,0,s,0xffffd84d);}
            }
            p.setAlpha(255);if(eaten){p.setColor(0xff48bd74);c.drawCircle(s*.62f,-s*.62f,s*.36f,p);text(c,"✓",s*.62f,-s*.50f,s*.5f,Color.WHITE,Paint.Align.CENTER,true);}c.restore();
        }
        private void drawStar(Canvas c,float cx,float cy,float r,int color){Path st=new Path();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float rr=(i%2==0)?r:r*.45f;float x=cx+(float)Math.cos(a)*rr,y=cy+(float)Math.sin(a)*rr;if(i==0)st.moveTo(x,y);else st.lineTo(x,y);}st.close();p.setColor(color);c.drawPath(st,p);}

        private void drawGoal(Canvas c,int buddy,float x,float y,boolean unlocked){
            float pulse=unlocked?(1f+(float)Math.sin(frame/120.0)*.08f):1f;c.save();c.translate(x,y);c.scale(pulse,pulse);if(unlocked){p.setShadowLayer(22,0,0,0xffffd348);}switch(buddy){
                case 0 -> {p.setColor(0xffbb7139);rounded(c,-48,-30,48,40,12,p.getColor());stroke.setColor(0xff8a4b27);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(8);c.drawArc(new RectF(-38,-70,38,10),180,180,false,stroke);stroke.setStyle(Paint.Style.FILL);}
                case 1 -> {p.setColor(0xffffc83e);c.drawOval(-45,-55,45,52,p);stroke.setColor(0xffb9821c);stroke.setStrokeWidth(7);for(int yy=-30;yy<=25;yy+=25)c.drawLine(-34,yy,34,yy,stroke);}
                case 2 -> {p.setColor(0xfff1c073);c.drawRect(-48,-20,48,48,p);Path roof=new Path();roof.moveTo(-65,-18);roof.lineTo(0,-76);roof.lineTo(65,-18);roof.close();p.setColor(0xffd75d4a);c.drawPath(roof,p);p.setColor(0xff6e4b34);c.drawRoundRect(-16,8,16,48,8,8,p);}
                case 3 -> {p.setColor(0xffb96935);rounded(c,-55,-15,55,48,10,p.getColor());p.setColor(0xffe55736);c.drawArc(new RectF(-55,-55,55,25),180,180,true,p);p.setColor(0xffffce43);c.drawRect(-8,-25,8,48,p);drawStar(c,0,8,15,0xffffe46b);}
                case 4 -> {p.setColor(0xffa56b3d);c.drawOval(-60,5,60,45,p);stroke.setColor(0xff7c4d2c);stroke.setStrokeWidth(7);for(int i=-45;i<=45;i+=20)c.drawLine(i,12,i+15,38,stroke);p.setColor(0xfffff4d0);c.drawOval(-30,-20,-3,20,p);c.drawOval(8,-25,35,18,p);}
                case 5 -> {p.setColor(Color.WHITE);c.drawCircle(-28,18,30,p);c.drawCircle(5,4,40,p);c.drawCircle(40,20,28,p);stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(12);int[] rc={0xffff6f76,0xffffc64b,0xff66d18a,0xff6a9fff};for(int i=0;i<4;i++){stroke.setColor(rc[i]);c.drawArc(new RectF(-58-i*4,-80-i*4,58+i*4,42+i*4),200,140,false,stroke);}stroke.setStyle(Paint.Style.FILL);}
                case 6 -> {p.setColor(0xffff6f9d);rounded(c,-52,-35,52,48,10,p.getColor());p.setColor(0xffffd5e3);c.drawRect(-7,-35,7,48,p);c.drawRect(-52,-5,52,9,p);p.setColor(0xffff4e8b);c.drawCircle(-18,-48,22,p);c.drawCircle(18,-48,22,p);}
            }p.clearShadowLayer();if(!unlocked){p.setColor(0xcc50627d);c.drawCircle(0,5,25,p);text(c,"🔒",0,16,24,Color.WHITE,Paint.Align.CENTER,true);}c.restore();
        }

        private void drawHeader(Canvas c,float ts){
            shadowCard(c,32,ts+22,112,ts+102,40,0xf7ffffff);text(c,"⚙",72,ts+76,42,0xff2f5aa0,Paint.Align.CENTER,true);
            bubbleText(c,"Morning",540,ts+78,64,0xff1857a5);bubbleText(c,"Mission",540,ts+142,70,0xffff684b);
            shadowCard(c,810,ts+28,1030,ts+100,36,0xf7ffffff);text(c,"Grown-Ups",920,ts+73,23,0xff3156a0,Paint.Align.CENTER,true);
        }
        private void drawMinuteBubbles(Canvas c,float ts){
            int selected=prefs.getInt("minutes",15);float[] xs={430,520,615,440,565,735,900};float[] ys={ts+260,ts+245,ts+255,ts+355,ts+345,ts+315,ts+350};float[] rs={33,38,43,48,58,72,83};int[] cols={0xffeaf5ff,0xffd9f8f2,0xfffff0a9,0xffe8ddff,0xffff8657,0xffb7d9ff,0xff93ecc1};
            for(int i=0;i<minuteValues.length;i++){float r=rs[i]+(selected==minuteValues[i]?7:0);p.setColor(cols[i]);c.drawCircle(xs[i],ys[i],r,p);if(selected==minuteValues[i]){stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(8);stroke.setColor(Color.WHITE);c.drawCircle(xs[i],ys[i],r,stroke);stroke.setStyle(Paint.Style.FILL);}text(c,String.valueOf(minuteValues[i]),xs[i],ys[i]+12,Math.max(23,r*.58f),0xff173c79,Paint.Align.CENTER,true);}
            text(c,"MINUTES",705,ts+205,18,0xff60728f,Paint.Align.CENTER,true);
        }
        private void drawTimer(Canvas c,float ts){
            shadowCard(c,350,ts+465,1012,ts+642,44,0xf9ffffff);long sec=(remainingMs()+999)/1000;String time=String.format(Locale.US,"%d:%02d",sec/60,sec%60);text(c,time,675,ts+565,78,0xff173460,Paint.Align.CENTER,true);text(c,"✎",944,ts+557,32,0xff5792bf,Paint.Align.CENTER,true);text(c,running?"LEFT TO GET READY":"Tap to customize",675,ts+615,22,0xff7386a2,Paint.Align.CENTER,false);
        }
        private String currentState(){if(allDone())return prefs.getBoolean("dance",true)?"DANCE":"CHEER";if(frame<cheerUntil)return"CHEER";long total=prefs.getInt("minutes",15)*60_000L;if(running&&remainingMs()<total*.2)return"HURRY";if(!running||total-remainingMs()<12000)return"SLEEPY";return active<taskKeys.length?taskKeys[active]:"CHEER";}
        private float homeActionTop=0;
        private void drawTasks(Canvas c,float ts){
            float start=ts+690; int max=Math.min(tasks.length,5); float y=start;
            text(c,"My Morning Routine",58,y-25,31,0xff243d70,Paint.Align.LEFT,true);shadowCard(c,850,y-62,1020,y-14,24,0xf6ffffff);text(c,"✎ Edit",935,y-30,20,0xff6083a8,Paint.Align.CENTER,true);
            for(int i=0;i<max;i++){boolean cur=i==active&&!done[i];int bg=done[i]?0xffeaf9ea:cur?0xfffff0b9:0xf7ffffff;shadowCard(c,48,y,1032,y+105,29,bg);drawActivityThumbnail(c,prefs.getInt("buddy",0),taskKeys[i],105,y+52,.26f);text(c,tasks[i],184,y+45,26,0xff20345e,Paint.Align.LEFT,true);text(c,done[i]?"Done! ★":activitySubtitle(taskKeys[i]),184,y+78,18,done[i]?0xff2d934f:0xff7b899d,Paint.Align.LEFT,false);if(cur){rounded(c,895,y+27,997,y+78,26,0xffff944e);text(c,"NOW",946,y+60,17,Color.WHITE,Paint.Align.CENTER,true);}y+=115;}
            if(tasks.length>max){text(c,"+ "+(tasks.length-max)+" more tasks",540,y+18,19,0xff617b9c,Paint.Align.CENTER,true);y+=30;}
            homeActionTop=Math.min(logicalH-bottomSafe()-205,y+20);
        }
        private String activitySubtitle(String k){return switch(k){case"WAKE"->"Rise and shine!";case"BATH"->"Bathroom break";case"DRESS"->"Ready for the day!";case"EAT"->"Fuel up!";case"BRUSH"->"Sparkly and clean!";case"HAIR"->"Looking great!";case"WASH"->"Fresh face!";case"SHOES"->"Almost there!";case"PACK"->"Ready for adventure!";case"JACKET"->"Warm and ready!";case"PET"->"Help your buddy!";case"VITAMIN"->"Healthy start!";default->"You can do it!";};}
        private void drawBottom(Canvas c){float b=logicalH-bottomSafe();float navTop=b-100;shadowCard(c,28,navTop,1052,b-10,40,0xf7ffffff);text(c,"⌂",190,navTop+43,30,0xff2979ed,Paint.Align.CENTER,true);text(c,"Today",190,navTop+70,16,0xff2979ed,Paint.Align.CENTER,true);text(c,"★",420,navTop+43,28,0xff9aa9bf,Paint.Align.CENTER,true);text(c,"Rewards",420,navTop+70,15,0xff8291a8,Paint.Align.CENTER,false);text(c,"☷",660,navTop+43,28,0xff9aa9bf,Paint.Align.CENTER,true);text(c,"Routine",660,navTop+70,15,0xff8291a8,Paint.Align.CENTER,false);text(c,"●",900,navTop+40,24,0xff9aa9bf,Paint.Align.CENTER,true);text(c,"Grown-Ups",900,navTop+70,15,0xff8291a8,Paint.Align.CENTER,false);
            float top=Math.min(homeActionTop,navTop-125);top=Math.max(top,tsFallback());shadowCard(c,105,top,975,top+100,50,0xffff7c43);text(c,"▶  START MORNING",540,top+64,31,Color.WHITE,Paint.Align.CENTER,true);homeActionTop=top; }
        private float tsFallback(){return topSafe()+1280;}

        private void drawBuddy(Canvas c,int buddy,float x,float y,String state,float s){
            Bitmap bm=buddyArt[Math.max(0,Math.min(buddy,buddyArt.length-1))]; if(bm==null)return;
            float bob=(float)Math.sin(frame/260.0)*7f; float rot=0f, sx=1f, sy=1f;
            if("DANCE".equals(state)){bob=(float)Math.sin(frame/95.0)*18f;rot=(float)Math.sin(frame/130.0)*9f;sx=1.02f+(float)Math.sin(frame/80.0)*.035f;sy=.98f;}
            else if("CHEER".equals(state)){bob=-10f+Math.abs((float)Math.sin(frame/105.0))*-22f;rot=(float)Math.sin(frame/120.0)*5f;}
            else if("HURRY".equals(state)){bob=(float)Math.sin(frame/70.0)*9f;rot=(float)Math.sin(frame/75.0)*7f;}
            else if("SLEEPY".equals(state)){bob=(float)Math.sin(frame/600.0)*4f;sy=.97f+(float)Math.sin(frame/650.0)*.02f;}
            else if("BRUSH".equals(state)||"EAT".equals(state)||"DRESS".equals(state)||"SHOES".equals(state)||"PACK".equals(state)){bob=(float)Math.sin(frame/150.0)*9f;rot=(float)Math.sin(frame/190.0)*3f;}
            c.save();c.translate(x,y+bob);c.rotate(rot);c.scale(s*sx,s*sy);
            float targetH=360f,targetW=targetH*bm.getWidth()/bm.getHeight();
            RectF dst=new RectF(-targetW/2,-targetH/2,targetW/2,targetH/2);p.setAlpha(255);c.drawBitmap(bm,null,dst,p);
            // Task-state prop overlays keep the polished buddy art while making the activity unmistakable.
            if("BRUSH".equals(state))text(c,"🪥",105,105,72,Color.WHITE,Paint.Align.CENTER,false);
            else if("EAT".equals(state))text(c,"🥣",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("DRESS".equals(state))text(c,"👕",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("SHOES".equals(state))text(c,"👟",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("PACK".equals(state))text(c,"🎒",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("HAIR".equals(state))text(c,"🪮",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("WASH".equals(state))text(c,"💦",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            else if("BATH".equals(state))text(c,"🚽",105,105,64,Color.WHITE,Paint.Align.CENTER,false);
            else if("JACKET".equals(state))text(c,"🧥",105,105,70,Color.WHITE,Paint.Align.CENTER,false);
            c.restore();
        }
        private void face(Canvas c,float eyeY,boolean animalNose){p.setColor(Color.WHITE);c.drawCircle(-43,eyeY,27,p);c.drawCircle(43,eyeY,27,p);p.setColor(0xff263554);c.drawCircle(-38,eyeY+2,10,p);c.drawCircle(48,eyeY+2,10,p);if(animalNose){p.setColor(0xff30303a);c.drawOval(-15,eyeY+31,15,eyeY+49,p);}p.setColor(0xff263554);c.drawArc(new RectF(-44,eyeY+35,44,eyeY+88),0,180,true,p);p.setColor(0xffff8791);c.drawOval(-20,eyeY+66,20,eyeY+83,p);}
        private void arms(Canvas c,String st,int color){stroke.setStrokeWidth(26);stroke.setColor(color);float up=(st.equals("DANCE")||st.equals("CHEER"))?-60:15;c.drawLine(-105,20,-165,55+up,stroke);c.drawLine(105,20,165,55+up,stroke);}
        private void drawBurgerCat(Canvas c,String st){p.setColor(0xfffff8e8);c.drawCircle(0,0,138,p);Path l=new Path();l.moveTo(-105,-85);l.lineTo(-100,-165);l.lineTo(-35,-118);l.close();c.drawPath(l,p);Path r=new Path();r.moveTo(105,-85);r.lineTo(100,-165);r.lineTo(35,-118);r.close();c.drawPath(r,p);arms(c,st,0xfffff8e8);face(c,-25,false);p.setColor(0xffffc85a);c.drawOval(-72,82,72,120,p);p.setColor(0xff65c766);c.drawRect(-65,105,65,122,p);p.setColor(0xff9b5a31);c.drawRect(-58,122,58,142,p);stroke.setStrokeWidth(5);stroke.setColor(0xff4a556d);for(int i=-1;i<=1;i++){c.drawLine(-120,i*18, -155,i*25,stroke);c.drawLine(120,i*18,155,i*25,stroke);}}
        private void drawBee(Canvas c,String st){p.setColor(0xffffdd5b);c.drawCircle(0,0,135,p);arms(c,st,0xffffdd5b);p.setColor(Color.WHITE);c.drawOval(-175,-15,-95,85,p);c.drawOval(95,-15,175,85,p);p.setColor(0xff2d3447);c.drawRect(-125,72,125,102,p);c.drawRect(-110,122,110,148,p);stroke.setStrokeWidth(9);stroke.setColor(0xff2d3447);c.drawLine(-48,-125,-72,-190,stroke);c.drawLine(48,-125,72,-190,stroke);face(c,-28,false);p.setColor(0xffffb636);Path cr=new Path();cr.moveTo(-48,-142);cr.lineTo(-25,-202);cr.lineTo(0,-155);cr.lineTo(28,-205);cr.lineTo(50,-142);cr.close();c.drawPath(cr,p);}
        private void drawPug(Canvas c,String st){p.setColor(0xffe7c79d);c.drawCircle(0,0,138,p);p.setColor(0xff594334);c.drawCircle(-105,-78,55,p);c.drawCircle(105,-78,55,p);arms(c,st,0xffe7c79d);p.setColor(0xffff8a3d);rounded(c,-112,70,112,160,28,p.getColor());stroke.setStrokeWidth(10);stroke.setColor(0xff573524);for(int x=-75;x<=75;x+=50)c.drawLine(x,85,x+25,145,stroke);face(c,-28,true);}
        private void drawShark(Canvas c,String st){p.setColor(0xff47b9ee);c.drawCircle(0,5,145,p);Path fin=new Path();fin.moveTo(-18,-130);fin.lineTo(12,-220);fin.lineTo(55,-120);fin.close();c.drawPath(fin,p);arms(c,st,0xff47b9ee);p.setColor(0xffffffff);c.drawOval(-92,-15,92,145,p);face(c,-45,false);Path mouth=new Path();mouth.moveTo(-55,30);mouth.lineTo(0,92);mouth.lineTo(55,30);mouth.close();p.setColor(0xffff5b64);c.drawPath(mouth,p);}
        private void drawDino(Canvas c,String st){p.setColor(0xff81d7b1);c.drawCircle(0,0,140,p);arms(c,st,0xff81d7b1);p.setColor(0xff4ca889);for(int i=0;i<5;i++)c.drawCircle(-70+i*35,-100+(i%2)*10,12,p);Path leaf=new Path();leaf.moveTo(0,-135);leaf.quadTo(-55,-225,-18,-238);leaf.quadTo(30,-220,0,-135);c.drawPath(leaf,p);Path leaf2=new Path();leaf2.moveTo(0,-135);leaf2.quadTo(65,-220,92,-182);leaf2.quadTo(65,-130,0,-135);c.drawPath(leaf2,p);face(c,-24,false);}
        private void drawCloudPup(Canvas c,String st){p.setColor(0xffa9dbf5);c.drawCircle(0,0,135,p);p.setColor(0xff83c7eb);c.drawOval(-190,-105,-85,80,p);c.drawOval(85,-105,190,80,p);arms(c,st,0xffa9dbf5);face(c,-20,true);p.setColor(Color.WHITE);c.drawCircle(-25,105,25,p);c.drawCircle(0,92,32,p);c.drawCircle(30,108,23,p);}
        private void drawPinkCat(Canvas c,String st){p.setColor(0xfffff7e9);c.drawCircle(0,0,138,p);Path l=new Path();l.moveTo(-105,-85);l.lineTo(-105,-165);l.lineTo(-40,-120);l.close();c.drawPath(l,p);Path r=new Path();r.moveTo(105,-85);r.lineTo(105,-165);r.lineTo(40,-120);r.close();c.drawPath(r,p);arms(c,st,0xfffff7e9);p.setColor(0xffff78ad);rounded(c,-115,72,115,158,26,p.getColor());stroke.setStrokeWidth(8);stroke.setColor(0xffffc4d8);for(int x=-80;x<=80;x+=40)c.drawLine(x,75,x,155,stroke);for(int yy=95;yy<=145;yy+=25)c.drawLine(-110,yy,110,yy,stroke);face(c,-28,false);p.setColor(0xffff5d9f);c.drawCircle(60,-150,38,p);c.drawCircle(115,-150,38,p);c.drawCircle(88,-150,27,p);}
        private void drawActivityThumbnail(Canvas c,int buddy,String key,float x,float y,float s){c.save();c.translate(x,y);c.scale(s,s);drawBuddy(c,buddy,0,0,key,1f);text(c,activityEmoji(key),120,110,80,0xff31466d,Paint.Align.CENTER,false);c.restore();}

        private void drawTimePicker(Canvas c){
            p.setColor(0x700e3560);c.drawRect(0,0,1080,logicalH,p);float ts=topSafe(),b=logicalH-bottomSafe();
            rounded(c,55,ts+105,1025,b-35,52,0xfff7fcff);bubbleText(c,"Customize Time",540,ts+190,48,0xff2473d4);text(c,"Pick a bubble or slide to any time",540,ts+235,20,0xff6d819f,Paint.Align.CENTER,false);text(c,"×",960,ts+175,48,0xff6f809b,Paint.Align.CENTER,true);
            shadowCard(c,250,ts+285,830,ts+455,46,0xffffffff);text(c,String.format(Locale.US,"%d:00",tempMinutes),540,ts+390,76,0xff173460,Paint.Align.CENTER,true);
            float[] bx={190,330,470,610,750,890,540};float[] by={ts+565,ts+565,ts+565,ts+565,ts+565,ts+565,ts+690};int[] vals={1,2,5,10,15,30,60};int[] cols={0xffdff7ef,0xffdff7ef,0xffffef9f,0xffe9ddff,0xffff9d70,0xffb9dcff,0xff9de7c1};for(int i=0;i<vals.length;i++){float r=vals[i]>=60?62:vals[i]>=30?57:49;p.setColor(cols[i]);c.drawCircle(bx[i],by[i],r,p);if(tempMinutes==vals[i]){stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(7);stroke.setColor(Color.WHITE);c.drawCircle(bx[i],by[i],r+4,stroke);stroke.setStyle(Paint.Style.FILL);}text(c,String.valueOf(vals[i]),bx[i],by[i]+10,28,0xff173c79,Paint.Align.CENTER,true);}
            text(c,"1",145,ts+815,18,0xff6d819f,Paint.Align.CENTER,true);text(c,"120",935,ts+815,18,0xff6d819f,Paint.Align.CENTER,true);rounded(c,165,ts+785,915,ts+805,10,0xffd7e4f3);float knob=165+(tempMinutes-1)/119f*750;rounded(c,165,ts+785,knob,ts+805,10,0xff2c7ded);p.setColor(0xffffffff);p.setShadowLayer(8,0,3,0x33000000);c.drawCircle(knob,ts+795,28,p);p.clearShadowLayer();
            shadowCard(c,150,b-150,930,b-60,45,0xff2879ed);text(c,"Set Time",540,b-92,29,Color.WHITE,Paint.Align.CENTER,true);
        }

        private void drawBuddyPicker(Canvas c){
            p.setColor(0x66000000);c.drawRect(0,0,1080,logicalH,p);float top=Math.max(150,topSafe()+90),bottom=logicalH-bottomSafe()-20;rounded(c,38,top,1042,bottom,52,0xfffbfdff);text(c,"Choose Your Buddy",540,top+78,45,0xff1d447e,Paint.Align.CENTER,true);text(c,"Tap a dancing buddy to hear them",540,top+116,20,0xff6e7fa1,Paint.Align.CENTER,false);text(c,"×",985,top+72,50,0xff62728d,Paint.Align.CENTER,true);
            float[] xs={180,420,660,900,260,540,820};float[] ys={top+265,top+265,top+265,top+265,top+585,top+585,top+585};int sel=prefs.getInt("buddy",0);
            for(int i=0;i<7;i++){float w=i<4?205:230,h=270;rounded(c,xs[i]-w/2,ys[i]-145,xs[i]+w/2,ys[i]+145,30,i==sel?0xffd9efff:0xffffffff);if(i==sel){stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(6);stroke.setColor(0xff3b91ff);c.drawRoundRect(xs[i]-w/2,ys[i]-145,xs[i]+w/2,ys[i]+145,30,30,stroke);stroke.setStyle(Paint.Style.FILL);}drawBuddy(c,i,xs[i],ys[i]-35,"DANCE",.46f);text(c,buddyNames[i],xs[i],ys[i]+100,18,0xff20375f,Paint.Align.CENTER,true);text(c,"♪ "+buddySoundWord(i),xs[i],ys[i]+130,17,0xff3e72c5,Paint.Align.CENTER,true);}
            rounded(c,135,bottom-100,945,bottom-25,38,0xff2879ed);text(c,"Use This Buddy",540,bottom-51,27,Color.WHITE,Paint.Align.CENTER,true);
        }
        private String buddySoundWord(int i){return new String[]{"nom!","buzz!","ruff!","splash!","rawr!","ding!","meow!"}[i];}
        private void drawCelebration(Canvas c){if(!celebrated){celebrated=true;playVictory();playBuddySound(prefs.getInt("buddy",0));}if(!prefs.getBoolean("confetti",true))return;int[] cs={0xffffc857,0xffff6b6b,0xff5ed6f2,0xff66cc88,0xff9b7bff};long tt=frame/45;for(int i=0;i<46;i++){float x=(i*173+tt*(i%5+1)*3)%1080,y=(i*97+tt*(i%7+3)*5)%(int)Math.max(900,logicalH);p.setColor(cs[i%cs.length]);c.drawRect(x-7,y-13,x+7,y+13,p);}}

        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=e.getX()/scale,y=e.getY()/scale;float ts=topSafe();
            if(timePicker){float b=logicalH-bottomSafe();if(x>915&&y<ts+230){timePicker=false;invalidate();return true;}float[] bx={190,330,470,610,750,890,540};float[] by={ts+565,ts+565,ts+565,ts+565,ts+565,ts+565,ts+690};int[] vals={1,2,5,10,15,30,60};for(int i=0;i<vals.length;i++){float dx=x-bx[i],dy=y-by[i];if(dx*dx+dy*dy<75*75){tempMinutes=vals[i];invalidate();return true;}}if(y>ts+755&&y<ts+840&&x>125&&x<955){tempMinutes=1+Math.round((Math.max(165,Math.min(915,x))-165)/750f*119f);invalidate();return true;}if(y>b-175){prefs.edit().putInt("minutes",tempMinutes).apply();timePicker=false;resetRoutine();return true;}return true;}
            if(buddyPicker){float top=Math.max(150,ts+90),bottom=logicalH-bottomSafe()-20;if(x>940&&y<top+100){buddyPicker=false;invalidate();return true;}float[] xs={180,420,660,900,260,540,820};float[] ys={top+265,top+265,top+265,top+265,top+585,top+585,top+585};for(int i=0;i<7;i++)if(Math.abs(x-xs[i])<115&&Math.abs(y-ys[i])<155){prefs.edit().putInt("buddy",i).apply();playBuddySound(i);invalidate();return true;}if(y>bottom-120){buddyPicker=false;invalidate();return true;}return true;}
            if(running){float bottom=logicalH-bottomSafe();if(y<ts+115&&x<270){showUnlockPin();return true;}if(y>bottom-185&&!allDone()){completeActive();return true;}if(y>bottom-315&&y<bottom-180&&!allDone()){completeActive();return true;}return true;}
            if(y<ts+125&&x<170){showSettingsProtected();return true;}if(y<ts+125&&x>790){showSettingsProtected();return true;}
            if(x<390&&y>ts+145&&y<ts+520){openBuddyPicker();return true;}
            float[] xs={430,520,615,440,565,735,900};float[] ys={ts+260,ts+245,ts+255,ts+355,ts+345,ts+315,ts+350};float[] rs={40,45,50,55,65,80,90};for(int i=0;i<7;i++){float dx=x-xs[i],dy=y-ys[i];if(dx*dx+dy*dy<rs[i]*rs[i]){prefs.edit().putInt("minutes",minuteValues[i]).apply();resetRoutine();return true;}}
            if(y>ts+440&&y<ts+645&&x>330){showCustomTimeDialog();return true;}
            if(y>ts+600&&y<ts+725&&x>820){showTaskEditor();return true;}
            float bottom=logicalH-bottomSafe();if(!running&&y>homeActionTop-10&&y<homeActionTop+120){startKidMode();return true;}if(running&&y>bottom-180){if(!allDone())completeActive();return true;}
            float ty=ts+675;int max=Math.min(tasks.length,6);for(int i=0;i<max;i++){if(y>=ty&&y<=ty+108){if(running&&i==active&&!done[i])completeActive();return true;}ty+=120;}return true;
        }
        private void completeActive(){if(active>=tasks.length||done[active])return;done[active]=true;cheerUntil=frame+1300;playBuddySound(prefs.getInt("buddy",0));while(active<tasks.length&&done[active])active++;if(allDone()){completionRemainingMs=Math.max(0,endAt-SystemClock.elapsedRealtime());performHapticFeedback(HapticFeedbackConstants.CONFIRM);}invalidate();}
        private void shadowCard(Canvas c,float l,float t,float r,float b,float rad,int color){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setShadowLayer(16,0,7,0x26000000);p.setColor(color);c.drawRoundRect(l,t,r,b,rad,rad,p);p.clearShadowLayer();}
        private void bubbleText(Canvas c,String s,float x,float y,float size,int fill){p.setTypeface(Typeface.create("sans-serif-rounded",Typeface.BOLD));p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(12);p.setColor(Color.WHITE);c.drawText(s,x,y,p);p.setStyle(Paint.Style.FILL);p.setShadowLayer(7,0,5,0x30000000);p.setColor(fill);c.drawText(s,x,y,p);p.clearShadowLayer();}
        private void rounded(Canvas c,float l,float t,float r,float b,float rad,int color){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(l,t,r,b,rad,rad,p);}
        private void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align,boolean bold){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(align);p.setTypeface(Typeface.create("sans-serif-rounded",bold?Typeface.BOLD:Typeface.NORMAL));c.drawText(s,x,y,p);}
    }

    static class PinSecurity{
        static boolean hasPin(SharedPreferences p){return p.contains("pin_hash")&&p.contains("pin_salt");}
        static void savePin(SharedPreferences p,String pin){try{byte[]salt=new byte[16];new SecureRandom().nextBytes(salt);byte[]hash=derive(pin,salt);p.edit().putString("pin_salt",Base64.getEncoder().encodeToString(salt)).putString("pin_hash",Base64.getEncoder().encodeToString(hash)).apply();}catch(Exception e){throw new RuntimeException(e);}}
        static boolean verify(SharedPreferences p,String pin){try{if(!hasPin(p))return false;byte[]salt=Base64.getDecoder().decode(p.getString("pin_salt",""));byte[]expected=Base64.getDecoder().decode(p.getString("pin_hash",""));return java.security.MessageDigest.isEqual(expected,derive(pin,salt));}catch(Exception e){return false;}}
        static byte[] derive(String pin,byte[]salt)throws Exception{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pin.toCharArray(),salt,120000,256)).getEncoded();}
    }
}
