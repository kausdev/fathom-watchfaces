package info.fathom.androidexperiments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;


public class BlinkerWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "BlinkerWatchFaceService";

    private static final long  INTERACTIVE_UPDATE_RATE_MS = 33;

    private static final float TAU = (float) (2 * Math.PI);
    private static final float QUARTER_TAU = TAU / 4;
    private static final float TO_DEGS = 360.0f / TAU;

    private static final int   BACKGROUND_COLOR_INTERACTIVE = Color.BLACK;
    private static final int   BACKGROUND_COLOR_AMBIENT = Color.BLACK;

    private static final int   TEXT_DIGITS_COLOR_INTERACTIVE = Color.WHITE;
    private static final int   TEXT_DIGITS_COLOR_AMBIENT = Color.WHITE;
    private static final float TEXT_DIGITS_HEIGHT = 0.2f;  // as a factor of screen height
    private static final float TEXT_DIGITS_BASELINE_HEIGHT = 0.43f;  // as a factor of screen height
    private static final float TEXT_DIGITS_RIGHT_MARGIN = 0.08f;  // as a factor of screen width

    private static final int   TEXT_GLANCES_COLOR_INTERACTIVE = Color.WHITE;
    private static final int   TEXT_GLANCES_COLOR_AMBIENT = Color.WHITE;
    private static final float TEXT_GLANCES_HEIGHT = 0.10f;  // as a factor of screen height
    private static final float TEXT_GLANCES_BASELINE_HEIGHT = TEXT_DIGITS_BASELINE_HEIGHT + 0.15f;  // as a factor of screen height
    private static final float TEXT_GLANCES_RIGHT_MARGIN = 0.08f;  // as a factor of screen width

    private static final String RALEWAY_TYPEFACE_PATH = "fonts/raleway-regular-enhanced.ttf";

    private static final int[] EYE_COLORS = {
            Color.rgb(255, 102, 51),
            Color.rgb(0, 153, 255),
            Color.rgb(125, 114, 163),
            Color.rgb(88, 148, 35),
            Color.rgb(238, 42, 123),
            Color.rgb(0, 167, 157),
            Color.rgb(117, 76, 41),
            Color.rgb(141, 198, 63),
            Color.rgb(196, 154, 108),
            Color.rgb(128, 130, 133)
    };
    private static final int   EYE_COLOR_COUNT = EYE_COLORS.length;

    private static final int   GLANCES_NEEDED_PER_NEW_EYE = 1;
    private static final float BLINK_TO_GLANCE_CHANCE_RATIO = 0.50f;                            // percent possibility of a blink event happening as compared to amount of glances

    private static final long  EYE_POPOUT_BASE_THRESHOLD = TimeUnit.MINUTES.toMillis(10);       // baseline threshold over which eyes will start popping out
    private static final long  EYE_POPOUT_PERIOD = TimeUnit.MINUTES.toMillis(5);                // beyond baseline, an eye will pop out every N millis

    private static final long  CONSECUTIVE_GLANCE_THRESHOLD = TimeUnit.SECONDS.toMillis(30);    // max time between glances to be considered consecutive
    private static final int   EYES_WIDE_OPEN_GLANCE_TRIGGER = 3;                               // how many consecutive glances are needed to trigger all eyes wide open

    private static final float GRAVITY_THRESHOLD = 1.0f;

    private static final int   RESET_HOUR = 4;                                                  // at which hour will watch face reset [0...23], -1 to deactivate

    // DEBUG
    private static final boolean DEBUG_LOGS = true;
    private static final boolean DEBUG_ACCELERATE_INTERACTION = true;  // adds more eyes and blink factor per glance
    private static final int     DEBUG_ACCELERATE_RATE = 5;  // each glance has xN times the effect
    private static final boolean DEBUG_SHOW_GLANCE_COUNTER = true;
    private static final boolean DEBUG_EYES_ROTATION = false;  // @TODO if they are going to be off forever, deactivate all sensing

    private static final boolean RANDOM_TIME_PER_GLANCE = false;  // this will add an hour to the time at each glance
    private static final int     RANDOM_MINUTES_INC = 300;







    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private static final int MSG_UPDATE_TIMER = 0;

        /* Handler to updateSize the screen depending on the message */
        final Handler mMainHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIMER:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mMainHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIMER, delayMs);
                        }
                        break;
                }
            }
        };


        //        private boolean mLowBitAmbient;
        //        private boolean mBurnInProtection;
        private boolean mAmbient, mScreenOn;

//        private Time mTime;
        private TimeManager mTimeManager;
        private String mTimeStr;
        private int mHourInt, mMinuteInt;
        private int mLastAmbientHour;
        private Paint mTextDigitsPaintInteractive, mTextDigitsPaintAmbient;

        private float mTextDigitsHeight, mTextDigitsBaselineHeight, mTextDigitsRightMargin;
        private Paint mTextGlancesPaintInteractive, mTextGlancesPaintAmbient;
        private float mTextGlancesHeight, mTextGlancesBaselineHeight, mTextGlancesRightMargin;
        private Typeface mTextTypeface;
        private final Rect textBounds = new Rect();
        private int mWidth;

        private int mHeight;
        private float mCenterX;
        private float mCenterY;
        private boolean mIsRound;
        private float mRadius;

        private int glances = 0;                // how many times did the watch go from ambient to interactive?
        private int consecutiveGlances = 1;     // amount of last consecutive glances
        private Time mCurrentGlance;
        private long mPrevGlance;

        private EyeMosaic eyeMosaic;
        private boolean mEyesPopulated = false;


        @Override
        public void onCreate(SurfaceHolder holder) {
            Log.v(TAG, "onCreate");
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(BlinkerWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mTextTypeface = Typeface.createFromAsset(getApplicationContext().getAssets(),
                    RALEWAY_TYPEFACE_PATH);

            mTextDigitsPaintInteractive = new Paint();
            mTextDigitsPaintInteractive.setColor(TEXT_DIGITS_COLOR_INTERACTIVE);
            mTextDigitsPaintInteractive.setTypeface(mTextTypeface);
            mTextDigitsPaintInteractive.setTextAlign(Paint.Align.RIGHT);
            mTextDigitsPaintInteractive.setAntiAlias(true);

            mTextDigitsPaintAmbient = new Paint();
            mTextDigitsPaintAmbient.setColor(TEXT_DIGITS_COLOR_AMBIENT);
            mTextDigitsPaintAmbient.setTypeface(mTextTypeface);
            mTextDigitsPaintAmbient.setTextAlign(Paint.Align.RIGHT);
            mTextDigitsPaintAmbient.setAntiAlias(false);

            mTextGlancesPaintInteractive = new Paint();
            mTextGlancesPaintInteractive.setColor(TEXT_GLANCES_COLOR_INTERACTIVE);
            mTextGlancesPaintInteractive.setTypeface(mTextTypeface);
            mTextGlancesPaintInteractive.setAntiAlias(true);
            mTextGlancesPaintInteractive.setTextAlign(Paint.Align.RIGHT);

            mTextGlancesPaintAmbient = new Paint();
            mTextGlancesPaintAmbient.setColor(TEXT_GLANCES_COLOR_AMBIENT);
            mTextGlancesPaintAmbient.setTypeface(mTextTypeface);
            mTextGlancesPaintAmbient.setAntiAlias(false);
            mTextGlancesPaintAmbient.setTextAlign(Paint.Align.RIGHT);

            eyeMosaic = new EyeMosaic();

//            mTime  = new Time();
            mTimeManager = new TimeManager() {
                @Override
                public void onReset() {
                    if (DEBUG_LOGS) Log.v(TAG, "RESETTING!!");

                }
            };
            if (RESET_HOUR >= 0) {
                mTimeManager.setOvernightResetHour(RESET_HOUR);
            }

            mCurrentGlance = new Time();
            mCurrentGlance.setToNow();
            mPrevGlance = mCurrentGlance.toMillis(false);

            registerScreenReceiver();
        }

        @Override
        public void onDestroy() {
            if (DEBUG_LOGS) Log.v(TAG, "onDestroy()");
            mMainHandler.removeMessages(MSG_UPDATE_TIMER);
            unregisterTimeZoneReceiver();
            unregisterScreenReceiver();
            super.onDestroy();
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            if (DEBUG_LOGS) Log.v(TAG, "onAmbientModeChanged: " + inAmbientMode);
            super.onAmbientModeChanged(inAmbientMode);

            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                invalidate();
            }

            /*
             * Whether the timer should be running depends on whether we're visible (as well as
             * whether we're in ambient mode), so we may need to start or stop the timer.
             */
            updateTimer();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            Log.v(TAG, "onVisibilityChanged: " + visible);
            super.onVisibilityChanged(visible);

            /*
            * Whether the timer should be running depends on whether we're visible
            * (as well as whether we're in ambient mode),
            * so we may need to start or stop the timer.
            */
            updateTimer();
        }


        private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (DEBUG_LOGS) Log.v(TAG, "Received intent: " + action);
                if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    if (DEBUG_LOGS) Log.v(TAG, "Screen ON");
                    mScreenOn = true;
                    onScreenChange(true);
                } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    if (DEBUG_LOGS) Log.v(TAG, "Screen OFF");
                    mScreenOn = false;
                    onScreenChange(false);
                }
            }
        };

        private void registerScreenReceiver() {
            if (DEBUG_LOGS) Log.v(TAG, "ScreenReceiver registered");
            BlinkerWatchFaceService.this.registerReceiver(mScreenReceiver,
                    new IntentFilter(Intent.ACTION_SCREEN_ON));
            BlinkerWatchFaceService.this.registerReceiver(mScreenReceiver,
                    new IntentFilter(Intent.ACTION_SCREEN_OFF));
        }

        private void unregisterScreenReceiver() {
            BlinkerWatchFaceService.this.unregisterReceiver(mScreenReceiver);
        }

        /**
         * This is a dedicated method to account for screen changes, which will happen when
         * the watch goes to ambient mode (if active), or if visibility changes (if ambient
         * mode is off).
         * This method should be called from a Broadcast receiver targeting Intent.ACTION_SCREEN_ON/OFF
         * @param turnedOn
         */
        public void onScreenChange(boolean turnedOn) {
            if (DEBUG_LOGS) Log.v(TAG, "onScreenChange: " + turnedOn);

            if (turnedOn) {
                registerTimeZoneReceiver();

                if (RANDOM_TIME_PER_GLANCE) {
                    mTimeManager.addRandomInc();
                }

//                glances++;
                int glanceInc = DEBUG_ACCELERATE_INTERACTION ? DEBUG_ACCELERATE_RATE : 1;
                glances += glanceInc;

                mCurrentGlance.setToNow();
                long glanceDiff = mCurrentGlance.toMillis(false) - mPrevGlance;
                if (DEBUG_LOGS) Log.v(TAG, "glanceDiff: " + glanceDiff);
                consecutiveGlances = glanceDiff < (CONSECUTIVE_GLANCE_THRESHOLD / DEBUG_ACCELERATE_RATE) ?
                        consecutiveGlances + 1 : 1;
                if (DEBUG_LOGS) Log.v(TAG, "consecutiveGlances: " + consecutiveGlances);

                eyeMosaic.newGlance(glanceInc, glanceDiff);


            } else {
                if (timelyReset()) {
                    if (DEBUG_LOGS) Log.v(TAG, "Resetting watchface");
                    glances = 0;
                    eyeMosaic.reset();
                }

                unregisterTimeZoneReceiver();

                mCurrentGlance.setToNow();
                mPrevGlance = mCurrentGlance.toMillis(false);

            }

            /*
             * Whether the timer should be running depends on whether we're visible (as well as
             * whether we're in ambient mode), so we may need to start or stop the timer.
             */
            updateTimer();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG_LOGS) Log.v(TAG, "onSurfaceChanged: " + format + " " + width + " " + height);
            super.onSurfaceChanged(holder, format, width, height);

            mWidth = width;
            mHeight = height;
            mCenterX = 0.50f * mWidth;
            mCenterY = 0.50f * mHeight;
            mRadius  = 0.50f * mWidth;

            mTextDigitsHeight = TEXT_DIGITS_HEIGHT * mHeight;
            mTextDigitsBaselineHeight = TEXT_DIGITS_BASELINE_HEIGHT * mHeight;
            mTextDigitsRightMargin = TEXT_DIGITS_RIGHT_MARGIN * mWidth;
            mTextDigitsPaintInteractive.setTextSize(mTextDigitsHeight);
            mTextDigitsPaintAmbient.setTextSize(mTextDigitsHeight);

            mTextGlancesHeight = TEXT_GLANCES_HEIGHT * mHeight;
            mTextGlancesBaselineHeight = TEXT_GLANCES_BASELINE_HEIGHT * mHeight;
            mTextGlancesRightMargin = TEXT_GLANCES_RIGHT_MARGIN * mWidth;
            mTextGlancesPaintInteractive.setTextSize(mTextGlancesHeight);
            mTextGlancesPaintAmbient.setTextSize(mTextGlancesHeight);

            if (!mEyesPopulated) {
                eyeMosaic.addEye(39, 21, 49);
                eyeMosaic.addEye(39, 73, 49);
                eyeMosaic.addEye(82, 45, 49);
                eyeMosaic.addEye(158, 45, 72);
                eyeMosaic.addEye(110, 145, 0);
                eyeMosaic.addEye(218, 21, 49);
                eyeMosaic.addEye(267, 45, 49);
                eyeMosaic.addEye(218, 73, 49);
                eyeMosaic.addEye(54, 138, 97);
                eyeMosaic.addEye(139, 155, 49);
                eyeMosaic.addEye(106, 195, 72);
                eyeMosaic.addEye(39, 212, 49);
                eyeMosaic.addEye(82, 240, 49);
                eyeMosaic.addEye(39, 265, 49);
                eyeMosaic.addEye(106, 285, 72);
                eyeMosaic.addEye(201, 195, 97);
                eyeMosaic.addEye(282, 195, 49);
                eyeMosaic.addEye(253, 253, 72);
                eyeMosaic.addEye(185, 269, 49);
                eyeMosaic.addEye(228, 298, 49);

                mEyesPopulated = true;

                int glanceInc = DEBUG_ACCELERATE_INTERACTION ? DEBUG_ACCELERATE_RATE : 1;
                glances += glanceInc;
                eyeMosaic.newGlance(glanceInc, 0);

            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (DEBUG_LOGS) Log.d(TAG, "onApplyWindowInsets");
            super.onApplyWindowInsets(insets);

            mIsRound = insets.isRound();
            if (DEBUG_LOGS) Log.v(TAG, "mIsRound? " + mIsRound);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (DEBUG_LOGS) Log.v(TAG, "Drawing canvas");

//            mTime.setToNow();
//            mHourInt = mTime.hour;
//            mMinuteInt = mTime.minute;
//            mTimeStr = (mHourInt % 12 == 0 ? 12 : mHourInt % 12) + ":" + String.format("%02d", mMinuteInt);

            mTimeManager.setToNow();  // if RANDOM_TIME_PER_GLANCE it won't update toNow
            mTimeStr = (mTimeManager.hour % 12 == 0 ? 12 : mTimeManager.hour % 12) + ":" + String.format("%02d", mTimeManager.minute);

            if (mAmbient) {
                canvas.drawColor(BACKGROUND_COLOR_AMBIENT);

                canvas.drawText(mTimeStr, mWidth - mTextDigitsRightMargin,
                        mTextDigitsBaselineHeight, mTextDigitsPaintAmbient);
                if (DEBUG_SHOW_GLANCE_COUNTER) canvas.drawText(Integer.toString(glances), mWidth - mTextGlancesRightMargin,
                        mTextGlancesBaselineHeight, mTextGlancesPaintAmbient);

            } else {
                canvas.drawColor(BACKGROUND_COLOR_INTERACTIVE);

                canvas.save();
                eyeMosaic.update();
                eyeMosaic.render(canvas);
                canvas.restore();

                canvas.drawText(mTimeStr, mWidth - mTextDigitsRightMargin,
                        mTextDigitsBaselineHeight, mTextDigitsPaintInteractive);
                if (DEBUG_SHOW_GLANCE_COUNTER) canvas.drawText(Integer.toString(glances), mWidth - mTextGlancesRightMargin,
                        mTextGlancesBaselineHeight, mTextGlancesPaintInteractive);

            }

        }

        private boolean mRegisteredTimeZoneReceiver = false;
        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
//                mTime.clear(intent.getStringExtra("time-zone"));
//                mTime.setToNow();
                mTimeManager.setTimeZone(intent);
            }
        };

        private void registerTimeZoneReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            BlinkerWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterTimeZoneReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            BlinkerWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        private void updateTimer() {
            mMainHandler.removeMessages(MSG_UPDATE_TIMER);
            if (shouldTimerBeRunning()) {
                mMainHandler.sendEmptyMessage(MSG_UPDATE_TIMER);
            }
        }

        /**
         * Returns whether the {@link #mMainHandler} timer should be running. The timer
         * should only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
//            mCardBounds.set(rect);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
//            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
//            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        // Checks if watchface should reset, like overnight
        private boolean timelyReset() {
            boolean reset = false;
            if (mHourInt == RESET_HOUR && mLastAmbientHour == RESET_HOUR - 1) {
                reset = true;
            }
            mLastAmbientHour = mHourInt;
            return reset;
        }


        private class TimeManager {

            private static final boolean DEBUG_FAKE_TIME = RANDOM_TIME_PER_GLANCE;
            private final long DEBUG_FAKE_TIME_INC = TimeUnit.MINUTES.toMillis(RANDOM_MINUTES_INC);
            private static final long DAY_IN_MILLIS = 86400000;

            private Time currentTime;
            public int year, month, monthDay, hour, minute, second;

            private boolean overnightReset;
            private int overnightResetHour;
            private Time nextResetTime;

            TimeManager() {
                overnightReset = false;
                overnightResetHour = 0;

                currentTime = new Time();
                currentTime.setToNow();

                setToNow();
            }

            public void setToNow() {
                if (!DEBUG_FAKE_TIME) {
                    currentTime.setToNow();
                }

                updateFields();
                if (overnightReset) resetCheck();
            }

            public void setTimeZone(Intent intent) {
                if (!DEBUG_FAKE_TIME) {
                    currentTime.clear(intent.getStringExtra("time-zone"));
                    currentTime.setToNow();
                }

                updateFields();
                if (overnightReset) resetCheck();
            }

            public void addRandomInc() {
                long rInc = (long) (DEBUG_FAKE_TIME_INC * Math.random());
                if (DEBUG_LOGS) Log.v(TAG, "Adding randomInc: " + rInc);

                currentTime.set(currentTime.toMillis(false) + rInc);

                updateFields();
                if (overnightReset) resetCheck();
            }

            public void resetCheck() {
                if (currentTime.after(nextResetTime)) {
                    nextResetTime = computeNextResetTime();
                    if (DEBUG_LOGS) Log.v(TAG, "Reset check: true");
                    onReset();
                }
            }

            public void onReset() { Log.v(TAG, "onReset"); }

            public void setOvernightResetHour(int hour_) {
                overnightReset = true;
                overnightResetHour = hour_;
                nextResetTime = computeNextResetTime();
            }

            private Time computeNextResetTime() {
                Time resetTime = new Time();
                resetTime.set(0, 0, overnightResetHour, monthDay, month, year);  // Set to today's reset time

                // If already past, add a day
                if (currentTime.after(resetTime)) {
                    resetTime.set(resetTime.toMillis(false) + DAY_IN_MILLIS);
                }

                if (DEBUG_LOGS) Log.v(TAG, "Next reset time: " + resetTime.format2445());

                return resetTime;
            }

            private void updateFields() {
                year = currentTime.year;
                month = currentTime.month;
                monthDay = currentTime.monthDay;
                hour = currentTime.hour;
                minute = currentTime.minute;
                second = currentTime.second;
            }

            public void toDebugLog() {
                Log.v(TAG, "--> Curr time: " + currentTime.format2445());
            }

        }







        class EyeMosaic {

            float blinkChance;
            private static final int BLINK_CHANCE_FACTOR = 5;

            Eye[] eyes;
            int eyeCount;

            int activeEyesCount;
            List<Eye> activeEyes = new ArrayList<>();
            List<Eye> inactiveEyes = new ArrayList<>();
            List<Eye> updateList = new ArrayList<>();

            boolean areWideOpen;

            EyeMosaic() {
                eyes = new Eye[8];
                eyeCount = 0;
                activeEyesCount = 0;
                blinkChance = 0;
            }

            void update() {
                // trigger a random eye to blink
                if (activeEyesCount > 0) {
                    if (Math.random() < blinkChance / (eyeCount * BLINK_CHANCE_FACTOR)) {
                        int id = (int) (activeEyesCount * Math.random());
                        Eye eye = activeEyes.get(id);
//                        if (!eye.isWideOpen) eye.blink();  // may affect an already blinking eye but not a wide open one

                        // TEMP TEST
                        if (!eye.isWideOpen) {
//                            if (eye.pupilPositionH != 1) {
//                                eye.lookCenterHorizontal();
//                            }
                            double r = Math.random();
                            if (r < 0.17) {
                                eye.lookLeft();
                            } else if (r < 0.33) {
                                eye.lookCenterHorizontal();
                            } else if (r < 0.50) {
                                eye.lookRight();
                            } else {
                                eye.blink();  // may affect an already blinking eye but not a wide open one
                            }

                            r = Math.random();
                            if (r < 0.17) {
                                eye.lookUp();
                            } else if (r < 0.33) {
                                eye.lookCenterVertical();
                            } else if (r < 0.50) {
                                eye.lookDown();
                            }

                        }
                    }
                }

//                // A separate update call on each frame for pupil interaction with lin_acc
//                for (Eye eye : activeEyes) {
//                    eye.updatePupil();
//                }

                for (Eye eye : updateList) {
                    eye.update();
                }

                // Unregister them from update list externally, to avoid iterator problems
                for (int i = updateList.size() - 1; i >= 0; i--) {
                    if (!updateList.get(i).needsUpdate) updateList.remove(updateList.get(i));
                }

            }

            void render(Canvas canvas) {
                if (DEBUG_LOGS) Log.v(TAG, "Rendering activeEyes: " + activeEyes.size());
                for (Eye eye : activeEyes) {
                    eye.render(canvas);
                }
            }

            void newGlance(int glanceInc, long glanceDiff) {
                // Reset wide open state from prev newGlance
                if (areWideOpen) {
                    for (Eye eye : activeEyes) {
                        eye.isWideOpen = false;
                        eye.open();
                    }
                    areWideOpen = false;
                }

                // Add/drop eyes  @TODO rely on glanceInc for these computations
                // Must eyes start popping out?
                if (glanceDiff > EYE_POPOUT_BASE_THRESHOLD) {
                    int popoutCount = (int) ((glanceDiff - EYE_POPOUT_BASE_THRESHOLD) / EYE_POPOUT_PERIOD);

                    if (DEBUG_ACCELERATE_INTERACTION) {
                        eyeMosaic.deactivateRandomEye(popoutCount * DEBUG_ACCELERATE_RATE);
                        eyeMosaic.increaseBlinkChance(-BLINK_TO_GLANCE_CHANCE_RATIO * DEBUG_ACCELERATE_RATE / GLANCES_NEEDED_PER_NEW_EYE);
                    } else {
                        eyeMosaic.deactivateRandomEye(popoutCount);
                        eyeMosaic.increaseBlinkChance(-BLINK_TO_GLANCE_CHANCE_RATIO / GLANCES_NEEDED_PER_NEW_EYE);
                    }

                // Or should they be added
                } else if (glances % GLANCES_NEEDED_PER_NEW_EYE == 0) {
                    if (DEBUG_ACCELERATE_INTERACTION) {
                        eyeMosaic.activateRandomEye(DEBUG_ACCELERATE_RATE);
                        eyeMosaic.increaseBlinkChance(BLINK_TO_GLANCE_CHANCE_RATIO * DEBUG_ACCELERATE_RATE / GLANCES_NEEDED_PER_NEW_EYE);
                    } else {
                        eyeMosaic.activateRandomEye(1);
                        eyeMosaic.increaseBlinkChance(BLINK_TO_GLANCE_CHANCE_RATIO / GLANCES_NEEDED_PER_NEW_EYE);
                    }
                }

                // Trigger eyes wide open?
                if (consecutiveGlances >= EYES_WIDE_OPEN_GLANCE_TRIGGER) {
                    for (Eye eye : activeEyes) {
                        eye.lookCenter();
                        eye.openWide();
                    }
                    areWideOpen = true;
                    consecutiveGlances = 1;  // @TERRENCE: do wide open once and reset
                }

            }

            // Creates inactive eyes to be activated later
            void addEye(float x_, float y_, float width_) {
                eyes[eyeCount] = new Eye(this, eyeCount, x_, y_, width_);
                inactiveEyes.add(eyes[eyeCount]);
                eyeCount++;

                // double the array size if necessary
                if (eyeCount == eyes.length) {
                    eyes = Arrays.copyOf(eyes, 2 * eyes.length);
                }
            }

            void activateRandomEye(int count) {
                for (int i = 0; i < count; i++) {
                    if (activeEyesCount >= eyeCount) return; // if no more inactive eyes in the list
                    Eye eye = inactiveEyes.get((int) (inactiveEyes.size() * Math.random()));
                    eye.activate();
                    activeEyes.add(eye);
                    inactiveEyes.remove(eye);
                    activeEyesCount++;
                }

            }

            void deactivateRandomEye(int count) {
                for (int i = 0; i < count; i++) {
                    if (activeEyesCount <= 0) return;
                    Eye eye = activeEyes.get((int) (activeEyes.size() * Math.random()));
                    eye.deactivate();
                    inactiveEyes.add(eye);
                    activeEyes.remove(eye);
                    activeEyesCount--;
                }
            }


            void increaseBlinkChance(float increment) {
                blinkChance += increment;
                if (blinkChance < 0) blinkChance = 0;
            }


            void reset() {
                for (Eye eye : activeEyes) {
                    eye.deactivate();
                    inactiveEyes.add(eye);
                }
                activeEyesCount = 0;
                blinkChance = 0;
//                inactiveEyes.clear();   // @TODO WAS THIS RIGHT???
                activeEyes.clear();
                updateList.clear();
            }

        }


        class Eye {
            // Constants
            final int   EYE_COLOR    = Color.rgb(252,245,245);
            final int   EYELID_COLOR = Color.rgb(0, 0, 0);
            static final int   PUPIL_COLOR = Color.BLACK;
            static final float BLINK_SPEED = 0.40f;
            static final int   ANIM_END_THRESHOLD = 1;      // pixel distance to stop animation
//            static final float HEIGHT_RATIO = 0.68f;      // height/width ratio
            static final float HEIGHT_RATIO = 0.50f;        // height/width ratio
            static final float IRIS_RATIO = 0.40f;          // irisDiameter/width ratio
            static final float PUPIL_RATIO = 0.22f;         // pupilDiameter/width ratio
            static final float WIDE_OPEN_RATIO = 0.65f;
            static final float HORIZONTAL_LOOK_RATIO = 0.45f;     // how far the pupil will travel laterally in relation to width/2
            static final float VERTICAL_LOOK_RATIO = 0.30f;       // idem
            static final float PUPIL_SPEED_HORIZONTAL = 0.50f;
            static final float PUPIL_SPEED_RADIUS = 0.15f;
            static final float PUPIL_DILATION_SIZE = 1.20f;
            static final float PUPIL_CONTRACTION_SIZE = 0.80f;

            static final float IRIS_OFFSET_RATIO = 0.058f;


//            static final float DEPTH_ACCEL_FACTOR = 0.05f;
//            static final float DEPTH_SPRING_FACTOR = 0.025f;
//            static final float FRICTION_FACTOR = 0.60f;

            EyeMosaic parent;

            int id;
            float x, y;
            float width, height;
            float irisRadius, irisOffset;
            float pupilRadius;
            float currentPupilRadius, targetPupilRadius;

            float currentAperture, targetAperture;

            int pupilPositionH;   // 0 = left, 1 = center, 2 = right
            float currentPupilX, targetPupilX;  // in relative coordinates
            int pupilPositionV;   // 0 = up, 1 = center, 2 = bottom
            float currentPupilY, targetPupilY;

            int irisColor;
            Path eyelid;
            Paint eyelidPaint, irisPaint, pupilPaint;
            Paint eyeLinerPaint;  // @TODO make parent static or something

            boolean isActive;
            boolean blinking, needsUpdate;
            boolean isWideOpen;

            Eye(EyeMosaic parent_, int id_, float x_, float y_, float width_) {
                parent = parent_;

                id = id_;
                x = x_ * mWidth / 320;
                y = y_ * mHeight / 320;
                width = width_;
                height = HEIGHT_RATIO * width;
                irisRadius = 0.5f * IRIS_RATIO * width;
                targetPupilRadius = currentPupilRadius = pupilRadius = 0.5f * PUPIL_RATIO * width;
//                velP = accP = 0;
                irisColor = randomColor();

                // calculate the offset of the iris one time per new eye
                irisOffset = irisRadius * IRIS_OFFSET_RATIO;

                currentAperture = 0;
                targetAperture = height;  // @TODO should this be 0?

                pupilPositionH = 1;
                currentPupilX = targetPupilX = 0;
                pupilPositionV = 1;
                currentPupilY = targetPupilY = 0;

                isActive = false;
                needsUpdate = false;
                blinking = false;

                eyelidPaint = new Paint();
                eyelidPaint.setColor(EYE_COLOR);
                eyelidPaint.setAntiAlias(true);

                irisPaint = new Paint();
                irisPaint.setColor(irisColor);
                irisPaint.setAntiAlias(true);

                pupilPaint = new Paint();
                pupilPaint.setColor(PUPIL_COLOR);
                pupilPaint.setAntiAlias(true);

                eyeLinerPaint = new Paint();
                eyeLinerPaint.setColor(EYELID_COLOR);
                eyeLinerPaint.setStyle(Paint.Style.STROKE);
                eyeLinerPaint.setStrokeWidth(2.0f);
                eyeLinerPaint.setAntiAlias(true);

                eyelid = new Path();
                rewindEyelid();
            }

            void render(Canvas canvas) {
                canvas.save();
                canvas.translate(x, y);
                canvas.save();
                canvas.clipPath(eyelid);
                canvas.drawCircle(0, 0, 0.5f * width, eyelidPaint);
                canvas.drawCircle(currentPupilX, currentPupilY - irisOffset, irisRadius, irisPaint);
                canvas.drawCircle(currentPupilX, currentPupilY - irisOffset, currentPupilRadius, pupilPaint);
                canvas.restore();
                canvas.drawPath(eyelid, eyeLinerPaint);
                canvas.restore();
            }


//            void updatePupil() {
//                accP = (DEPTH_ACCEL_FACTOR * linear_acceleration[2] + DEPTH_SPRING_FACTOR * (pupilRadius - currentPupilRadius));
//                velP += accP;
//                velP *= FRICTION_FACTOR;
//                currentPupilRadius += velP;
//                if (currentPupilRadius > 0.90f * irisRadius) currentPupilRadius = 0.90f * irisRadius;
//            }

            boolean update() {
                float diffH = targetAperture - currentAperture;
                currentAperture = Math.abs(diffH) < ANIM_END_THRESHOLD ?
                        targetAperture :
                        currentAperture + BLINK_SPEED * (diffH);

                float diffPX = targetPupilX - currentPupilX;
                currentPupilX = Math.abs(diffPX) < ANIM_END_THRESHOLD ?
                        targetPupilX :
                        currentPupilX + PUPIL_SPEED_HORIZONTAL * (diffPX);

                float diffPY = targetPupilY - currentPupilY;
                currentPupilY = Math.abs(diffPY) < ANIM_END_THRESHOLD ?
                        targetPupilY :
                        currentPupilY + PUPIL_SPEED_HORIZONTAL * (diffPY);

                float diffPR = targetPupilRadius - currentPupilRadius;
                currentPupilRadius = Math.abs(diffPR) < ANIM_END_THRESHOLD ?
                        targetPupilRadius :
                        currentPupilRadius + PUPIL_SPEED_RADIUS * (diffPR);
                rewindEyelid();

                // If completed an animation
                if (currentAperture == targetAperture &&
                        currentPupilX == targetPupilX &&
                        currentPupilY == targetPupilY &&
                        currentPupilRadius == targetPupilRadius) {

                    unregisterUpdate();

                    if (blinking) {
                        if (targetAperture == 0) {
                            open();  // restart animation (and blinking remains true)
                        } else {
                            blinking = false;
                        }
                    }
                }

                return needsUpdate;
            }

            void rewindEyelid() {
                eyelid.rewind();
                eyelid.moveTo(-0.5f * width, 0);
                eyelid.quadTo(0, -currentAperture, 0.5f * width, 0);
                eyelid.quadTo(0,  currentAperture, -0.5f * width, 0);
                eyelid.close();
            }

            void activate() {
                isActive = true;
                open();
            }

            // hard deactivate with no transition
            void deactivate() {
                isActive = false;
                needsUpdate = false;
                blinking = false;
                isWideOpen = false;
                currentAperture = 0;
                targetAperture = height;  // @TODO should this be 0?
                pupilPositionH = 1;
                currentPupilX = 0;
                targetPupilX = 0;
            }

            // hard reset with no transition
            void reset() {
                deactivate();
                isActive = true;
                currentAperture = height;
                rewindEyelid();
            }

            void open() {
                targetAperture = height;
                targetPupilRadius = pupilRadius;
                registerUpdate();
            }

            void close() {
                targetAperture = 0;
                targetPupilRadius = PUPIL_DILATION_SIZE * pupilRadius;
                registerUpdate();
            }

            void blink() {
                close();
                blinking = true;
            }

            void openWide() {
                targetAperture = WIDE_OPEN_RATIO * width;
                targetPupilRadius = PUPIL_CONTRACTION_SIZE * pupilRadius;
                isWideOpen = true;
                registerUpdate();
            }

            void lookCenter() {
                lookCenterHorizontal();
                lookCenterVertical();
            }

            void lookLeft() {
                targetPupilX = -HORIZONTAL_LOOK_RATIO * width / 2;
                pupilPositionH = 0;
                registerUpdate();
            }

            void lookCenterHorizontal() {
                targetPupilX = 0;
                pupilPositionH = 1;
                registerUpdate();
            }

            void lookRight() {
                targetPupilX = HORIZONTAL_LOOK_RATIO * width / 2;
                pupilPositionH = 2;
                registerUpdate();
            }

            void lookUp() {
                targetPupilY = - VERTICAL_LOOK_RATIO * height / 2;
                pupilPositionV = 0;
                registerUpdate();
            }

            void lookCenterVertical() {
                targetPupilY = 0;
                pupilPositionV = 0;
                registerUpdate();
            }

            void lookDown() {
                targetPupilY = VERTICAL_LOOK_RATIO * height / 2;
                pupilPositionV = 0;
                registerUpdate();
            }

            int randomColor() {
                return EYE_COLORS[ (int) (EYE_COLOR_COUNT * Math.random()) ];
            }

            void registerUpdate() {
                if (!parent.updateList.contains(this)) {
                    parent.updateList.add(this);
                }
                needsUpdate = true;
            }

            void unregisterUpdate() {
                needsUpdate = false;
            }
        }
    }






}
