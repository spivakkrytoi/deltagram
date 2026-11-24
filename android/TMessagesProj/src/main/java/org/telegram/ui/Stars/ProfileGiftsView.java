package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;
import static org.telegram.ui.Stars.StarsIntroActivity.setGiftImage;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;
    private final Theme.ResourcesProvider resourcesProvider;

    // Free positioning of pinned gifts ("bubbles"). Positions are stored locally
    // per account / profile / gift, because the MTProto API has no free placement.
    private static final long DRAG_LONG_PRESS_MS = 350;
    private static final String PREFS_NAME = "profile_gifts_positions";
    private final boolean canDrag;

    private static final java.util.ArrayList<java.lang.ref.WeakReference<ProfileGiftsView>> INSTANCES = new java.util.ArrayList<>();
    private final Paint skinRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skinGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleRimRect = new RectF();

    /** Punches the avatar silhouette out of a bubble that orbits behind it. */
    private final Paint avatarMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF depthLayerRect = new RectF();
    /** Reused depth-sorting buffer (back-most bubble first). */
    private Gift[] drawOrder = new Gift[0];

    /** Radial equalizer + glow around the avatar, driven by the music player. */
    private final Paint musicBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint musicGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient musicGlowShader;
    private float musicGlowRadius = -1f;
    private int musicGlowAccent;
    private float musicKick, musicLevel, musicTime;
    private float lastSpinProgress;

    /**
     * "Мяууу" companion: Paimon is pre-rendered from the bundled Blockbench
     * model into sprite sheets (res/drawable-nodpi), so the profile does not
     * need a 3D engine. She flies around the profile, and from time to time
     * flies up to one of the pinned gift bubbles and stares at it.
     */
    private static final int CAT_SHEET_COLS = 8;
    private static final int CAT_FLY_FRAMES = 24;
    private static final int CAT_FLY_LOOP_MS = 1600;
    private static final int CAT_HAPPY_FRAMES = 16;
    private static final int CAT_SURPRISED_FRAMES = 16;
    private static final int CAT_EMOTION_MS = 1200;
    private static final int CAT_WANDER = 0;
    private static final int CAT_TO_GIFT = 1;
    private static final int CAT_LOOK = 2;
    /** Carrying the "gift of the day" to the centre. */
    private static final int CAT_CARRY = 3;
    /** Holding the gift in the centre while its card is shown. */
    private static final int CAT_SHOWCASE = 4;
    /** Flying the gift back to its own slot. */
    private static final int CAT_RETURN = 5;

    private Bitmap catFlySheet, catHappySheet, catSurprisedSheet;
    private boolean catLoadFailed;
    private final Paint catPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint catHeartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint catGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint catOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect catSrcRect = new Rect();
    private final RectF catDstRect = new RectF();
    private final Path catHeartPath = new Path();

    private int catState = CAT_WANDER;
    private boolean catInitialized;
    private float catX, catY, catVX, catVY;
    private float catTargetX, catTargetY;
    private float catFacing = 1f;
    private float catFacingTarget = 1f;
    private float catTilt, catAttention, catAlpha;
    private float catBob;
    private long catRetargetAt, catNextLookAt, catLookStart, catLookEnd;
    private long catFlyStart;
    private Gift catGift;
    private final Random catRandom = new Random();

    /**
     * "Мяууу-почтальон" ("gift of the day"): once a day the companion takes
     * one pinned gift, carries it above the avatar and shows a collectible card
     * with its title, number and date, then returns the gift to its own slot.
     */
    private Gift heldGift;
    private long heldAt;
    private long showcaseUntil;
    private long dailyCheckAt;
    private long dailyAttemptAt;
    private boolean pendingCarry;
    private boolean catTapPending;
    private String heldSender;
    private float cardAlpha;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final Path cardPath = new Path();

    /** User-drawn "living constellations" between gift bubbles. */
    private final ArrayList<long[]> constellations = new ArrayList<>();
    private boolean constellationsLoaded;
    private boolean consEdit;
    private Gift consFrom;
    private float consTouchX, consTouchY;
    private boolean consTapEmpty;
    private float consButtonX, consButtonY, consButtonR = dp(19);
    private long consHintStart;
    private boolean consHintShown;
    private final Path linkPath = new Path();
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint consUiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint consTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path consStarPath = new Path();
    /** Visible constellation toggle, computed in the draw pass for hit-testing. */
    private boolean consButtonVisible;

    /** Positions are re-generated from a stable seed, like Telegram for iOS does. */
    private final Random bubbleRandom = new Random(System.currentTimeMillis() / 1000L);
    private boolean positionsDirty = true;
    private int positionsWidth, positionsHeight;
    private float positionsExpandY, positionsCx, positionsCy;
    private final long animationsStart = SystemClock.uptimeMillis();

    /** Repaints every visible profile after the user picks another bubble skin. */
    public static void notifySkinChanged() {
        for (int i = INSTANCES.size() - 1; i >= 0; i--) {
            final ProfileGiftsView view = INSTANCES.get(i).get();
            if (view == null) {
                INSTANCES.remove(i);
            } else {
                view.invalidate();
            }
        }
    }

    private Gift draggingGift;
    private float dragCx, dragCy;
    /** Lagged render position of the dragged bubble (elastic follow). */
    private float dragRenderX, dragRenderY;
    /** Animated lift of the dragged bubble. */
    private float dragLift = 1f;
    /** Stretch along the motion direction, 0..0.2. */
    private float dragStretch, dragStretchAngle;
    /** Smoothed finger velocity, px per millisecond. */
    private float dragVelX, dragVelY;
    private float lastMoveX, lastMoveY;
    private long lastMoveTime;
    private long dragStartTime;
    private long lastFrameTime;
    private VelocityTracker velocityTracker;
    /** Release velocity, px per second. */
    private float lastVelocityX, lastVelocityY;

    /** Fling spin around the avatar. */
    private long flingSpinStart = -1L;
    private long flingSpinDuration = 2800L;
    private int flingSpinTurns = 1;
    private int flingSpinDir = 1;

    private float dragTouchOffsetX, dragTouchOffsetY;
    private float downX, downY;
    private boolean dragLongPressPosted;
    private final Runnable dragLongPressRunnable = this::startDrag;

    /** How long the bubble has been held still, drives the vortex strength. */
    private float dragHoldMs;
    private float dragVortex;

    /** Size of the avatar box used for the last layout, for normalised offsets. */
    private float lastAvatarWidth, lastAvatarHeight;

    private boolean active = true;
    public void setActive(boolean f) {
        this.active = f;
    }

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;

        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;

        this.resourcesProvider = resourcesProvider;

        this.canDrag = dialogId == UserConfig.getInstance(currentAccount).getClientUserId();
        avatarMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        catOutlinePaint.setColorFilter(new PorterDuffColorFilter(0x66000000, PorterDuff.Mode.SRC_IN));
        catOutlinePaint.setAlpha(170);
        INSTANCES.add(new java.lang.ref.WeakReference<>(this));
    }

    public float expandProgress, collapseProgress;
    public boolean isOpening;

    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    public void setCollapseProgress(float progress, boolean isOpening) {
        this.isOpening = isOpening;
        float newProgress = Utilities.clamp01((progress - 0.3f) / 0.7f);
        if (this.collapseProgress != newProgress) {
            this.collapseProgress = newProgress;
            invalidate();
        }
    }

    private float actionBarProgress;

    public void setActionBarActionMode(float progress) {
//        if (Theme.isCurrentThemeDark()) {
//            return;
//        }
        actionBarProgress = progress;
        invalidate();
    }


    private float left, right, cy;
    private float expandY, maxExpandY;
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated, int maxExpandY) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        this.maxExpandY = maxExpandY + cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float y) {
        this.expandY = y;
        invalidate();
    }

    private float progressToInsets = 1f;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            if (gift.imageReceiver != null) {
                gift.imageReceiver.onAttachedToWindow();
            }
        }

        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);

        cancelDragLongPress();
        finishDrag(false);
        releaseVelocityTracker();
        flingSpinStart = -1L;
        lastFrameTime = 0L;
        lastSpinProgress = 0f;
        dragHoldMs = 0f;
        dragVortex = 0f;
        musicKick = musicLevel = 0f;
        catAttention = 0f;
        catGift = null;
        heldGift = null;
        heldSender = null;
        cardAlpha = 0f;
        showcaseUntil = 0L;
        pendingCarry = false;
        catTapPending = false;
        dailyCheckAt = 0L;
        dailyAttemptAt = 0L;
        catState = CAT_WANDER;
        catInitialized = false;
        catAlpha = 0f;
        consEdit = false;
        consFrom = null;
        releaseCat();

        for (Gift gift : gifts) {
            if (gift.imageReceiver != null) {
                gift.imageReceiver.onDetachedFromWindow();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            if ((long) args[0] == dialogId) {
                update();
            }
        }
    }

    // ---------------------------------------------------------------- "Мяууу"

    private void ensureCatLoaded() {
        if (catFlySheet != null || catLoadFailed) {
            return;
        }
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            catFlySheet = BitmapFactory.decodeResource(getResources(), R.drawable.deltagram_myaau_fly, options);
            catHappySheet = BitmapFactory.decodeResource(getResources(), R.drawable.deltagram_myaau_happy, options);
            catSurprisedSheet = BitmapFactory.decodeResource(getResources(), R.drawable.deltagram_myaau_surprised, options);
        } catch (Throwable ignore) {
        }
        if (catFlySheet == null || catHappySheet == null || catSurprisedSheet == null) {
            releaseCat();
            catLoadFailed = true;
        }
    }

    private void releaseCat() {
        if (catFlySheet != null) {
            catFlySheet.recycle();
            catFlySheet = null;
        }
        if (catHappySheet != null) {
            catHappySheet.recycle();
            catHappySheet = null;
        }
        if (catSurprisedSheet != null) {
            catSurprisedSheet.recycle();
            catSurprisedSheet = null;
        }
    }

    private float catTop() {
        return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(10);
    }

    private float catBottom(float viewBottom) {
        final float nav = Math.max(AndroidUtilities.navigationBarHeight, dp(8));
        return Math.max(catTop() + dp(80), Math.min(viewBottom, getHeight()) - nav - dp(12));
    }

    private void resetCatPosition(long now, float acx, float acy, float avatarRadius, float viewBottom) {
        catInitialized = true;
        final double angle = catRandom.nextDouble() * Math.PI * 2.0;
        final float distance = avatarRadius * (1.55f + catRandom.nextFloat() * 0.55f);
        catX = acx + (float) Math.cos(angle) * distance;
        catY = acy + (float) Math.sin(angle) * distance;
        catVX = catVY = 0f;
        catFacing = catFacingTarget = catX < acx ? -1f : 1f;
        catTilt = 0f;
        catAttention = 0f;
        catAlpha = 0f;
        catBob = 0f;
        catFlyStart = now;
        catState = CAT_WANDER;
        catGift = null;
        chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
        catNextLookAt = now + 3500L + catRandom.nextInt(4000);
    }

    private void chooseCatWanderTarget(long now, float acx, float acy, float avatarRadius, float viewBottom) {
        final float w = Math.max(getWidth(), dp(200));
        final float top = catTop();
        final float bottom = catBottom(viewBottom);
        ensureConstellationsLoaded();
        if (DeltamentProfileMusic.isConstellationsEnabled() && !constellations.isEmpty()
                && catRandom.nextFloat() < 0.35f) {
            // Sometimes Paimon flies along a user-drawn constellation instead of
            // a random point, which makes the links feel alive.
            final long[] link = constellations.get(catRandom.nextInt(constellations.size()));
            final Gift target = catRandom.nextBoolean() ? findGiftById(link[0]) : findGiftById(link[1]);
            if (target != null) {
                catTargetX = clamp(target.drawX + (catRandom.nextFloat() - 0.5f) * dp(36),
                        dp(18), w - dp(18));
                catTargetY = clamp(target.drawY + (catRandom.nextFloat() - 0.5f) * dp(36), top, bottom);
                catRetargetAt = now + 2200L + catRandom.nextInt(2600);
                return;
            }
        }
        if (catRandom.nextFloat() < 0.45f) {
            final double angle = catRandom.nextDouble() * Math.PI * 2.0;
            final float distance = avatarRadius * (1.3f + catRandom.nextFloat() * 0.8f);
            catTargetX = acx + (float) Math.cos(angle) * distance;
            catTargetY = acy + (float) Math.sin(angle) * distance;
        } else {
            catTargetX = dp(24) + catRandom.nextFloat() * Math.max(1f, w - dp(48));
            catTargetY = top + catRandom.nextFloat() * Math.max(1f, bottom - top);
        }
        catTargetX = clamp(catTargetX, dp(18), w - dp(18));
        catTargetY = clamp(catTargetY, top, bottom);
        catRetargetAt = now + 2200L + catRandom.nextInt(2600);
    }

    /** Prefers pinned gifts, exactly like the requirement says. */
    private Gift pickCatGift() {
        if (gifts.isEmpty()) {
            return null;
        }
        Gift best = null;
        int seen = 0;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int i = 0; i < gifts.size(); i++) {
                final Gift gift = gifts.get(i);
                if (gift == draggingGift || (pass == 0 && !gift.pinned)) {
                    continue;
                }
                seen++;
                if (catRandom.nextInt(seen) == 0) {
                    best = gift;
                }
            }
        }
        return best;
    }

    private float catSpotX(Gift gift, float acx, float acy) {
        final float dx = gift.drawX - acx;
        final float dy = gift.drawY - acy;
        final float d = (float) Math.hypot(dx, dy);
        final float nx = d > 1f ? dx / d : 1f;
        return gift.drawX + nx * dp(58);
    }

    private float catSpotY(Gift gift, float acx, float acy) {
        final float dx = gift.drawX - acx;
        final float dy = gift.drawY - acy;
        final float d = (float) Math.hypot(dx, dy);
        final float ny = d > 1f ? dy / d : 0f;
        return gift.drawY + ny * dp(58);
    }

    private void updateCat(float dt, long now, float acx, float acy, float avatarRadius, float viewBottom, float visibility) {
        if (!DeltamentProfileMusic.isCatEnabled()) {
            return;
        }
        ensureCatLoaded();
        if (catFlySheet == null) {
            return;
        }
        if (!catInitialized) {
            resetCatPosition(now, acx, acy, avatarRadius, viewBottom);
        }

        catBob += dt / 1000f;
        catAlpha += ((visibility > 0.12f ? 1f : 0f) - catAlpha) * (1f - (float) Math.exp(-dt / 260f));

        if (catGift != null && catGift == draggingGift) {
            catGift = null;
            cancelGiftOfDay();
            chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
        }
        if (dailyCheckAt == 0L) {
            dailyCheckAt = now + 2400L;
        }
        if (heldGift != null && !gifts.contains(heldGift)) {
            cancelGiftOfDay();
            chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
        }

        final float carryX = acx;
        final float carryY = Math.max(catTop() + dp(6), acy - avatarRadius - dp(40));

        if (catState == CAT_LOOK) {
            if (catGift == null || !gifts.contains(catGift) || now >= catLookEnd) {
                catState = CAT_WANDER;
                catGift = null;
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            }
        } else if (catState == CAT_TO_GIFT) {
            if (catGift == null || !gifts.contains(catGift)) {
                catState = CAT_WANDER;
                catGift = null;
                pendingCarry = false;
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            }
        } else if (catState == CAT_CARRY) {
            if (heldGift == null) {
                cancelGiftOfDay();
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            }
        } else if (catState == CAT_SHOWCASE) {
            if (heldGift == null) {
                cancelGiftOfDay();
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            } else if (now >= showcaseUntil) {
                catState = CAT_RETURN;
            }
        } else if (catState == CAT_RETURN) {
            if (heldGift == null) {
                cancelGiftOfDay();
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            }
        } else {
            final float dx = catTargetX - catX;
            final float dy = catTargetY - catY;
            if (now >= catRetargetAt || dx * dx + dy * dy < dp(8) * dp(8)) {
                chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            }
            if (now >= catNextLookAt) {
                final Gift gift = pickCatGift();
                if (gift != null) {
                    catGift = gift;
                    catState = CAT_TO_GIFT;
                } else {
                    catNextLookAt = now + 5000L;
                }
            }
            maybeStartDailyGift(now, avatarRadius);
        }

        if (catState == CAT_CARRY || catState == CAT_SHOWCASE) {
            catTargetX = carryX;
            catTargetY = carryY;
        } else if (catState == CAT_RETURN && heldGift != null) {
            // Land exactly on the bubble's own slot, so the gift can be
            // released without it visually jumping anywhere.
            catTargetX = heldGift.drawX;
            catTargetY = heldGift.drawY - dp(23);
        } else if (catGift != null) {
            catTargetX = catSpotX(catGift, acx, acy);
            catTargetY = catSpotY(catGift, acx, acy);
            if (catState == CAT_LOOK) {
                catFacingTarget = catGift.drawX >= catX ? 1f : -1f;
            }
        }

        final float top = catTop();
        final float bottom = catBottom(viewBottom);
        final float minX = dp(18);
        final float maxX = Math.max(minX, getWidth() - dp(18));

        float tx = clamp(catTargetX, minX, maxX);
        float ty = clamp(catTargetY, top, bottom);
        if (catState == CAT_LOOK) {
            // Hover next to the gift instead of freezing in place.
            ty += (float) Math.sin(catBob * 2.2f) * dp(2.6f);
            tx += (float) Math.cos(catBob * 1.7f) * dp(1.6f);
        } else if (catState == CAT_SHOWCASE) {
            // Gentle "showing it to you" bob while the card is open.
            ty += (float) Math.sin(catBob * 1.9f) * dp(3.2f);
            tx += (float) Math.cos(catBob * 1.3f) * dp(1.4f);
        }

        final float dx = tx - catX;
        final float dy = ty - catY;
        final float distance = (float) Math.hypot(dx, dy);
        final float speed = catState == CAT_TO_GIFT || catState == CAT_CARRY ? dpf2(165)
                : catState == CAT_RETURN ? dpf2(150)
                : catState == CAT_SHOWCASE ? dpf2(0)
                : catState == CAT_LOOK ? dpf2(26) : dpf2(62);
        float desiredVX = 0f, desiredVY = 0f;
        if (distance > 0.5f && speed > 0f) {
            desiredVX = dx / distance * speed;
            desiredVY = dy / distance * speed;
        }
        final float steer = 1f - (float) Math.exp(-dt
                / (catState == CAT_LOOK || catState == CAT_SHOWCASE ? 300f : 210f));
        catVX += (desiredVX - catVX) * steer;
        catVY += (desiredVY - catVY) * steer;
        catX += catVX * dt / 1000f;
        catY += catVY * dt / 1000f;

        if (catX < minX) {
            catX = minX;
            catVX = Math.abs(catVX) * 0.35f;
        } else if (catX > maxX) {
            catX = maxX;
            catVX = -Math.abs(catVX) * 0.35f;
        }
        if (catY < top) {
            catY = top;
            catVY = Math.abs(catVY) * 0.35f;
        } else if (catY > bottom) {
            catY = bottom;
            catVY = -Math.abs(catVY) * 0.35f;
        }

        if ((catState == CAT_WANDER || catState == CAT_CARRY || catState == CAT_RETURN)
                && Math.abs(catVX) > dp(6)) {
            catFacingTarget = catVX > 0f ? 1f : -1f;
        }
        catFacing += (catFacingTarget - catFacing) * (1f - (float) Math.exp(-dt / 150f));

        final float tiltTarget = clamp(catVX / dpf2(620f), -1f, 1f) * 7f
                + clamp(catVY / dpf2(700f), -1f, 1f) * 3f
                + (catState == CAT_LOOK && catGift != null
                        ? (catGift.drawX >= catX ? 8f : -8f) : 0f);
        catTilt += (tiltTarget - catTilt) * (1f - (float) Math.exp(-dt / 200f));

        final boolean attentive = (catState == CAT_LOOK || catState == CAT_CARRY
                || catState == CAT_SHOWCASE || catState == CAT_RETURN) && catGift != null;
        final float attentionTarget = attentive ? 1f : 0f;
        catAttention += (attentionTarget - catAttention) * (1f - (float) Math.exp(-dt / 240f));

        if (catState == CAT_TO_GIFT && catGift != null && distance < dp(6)) {
            if (pendingCarry) {
                pendingCarry = false;
                heldGift = catGift;
                heldAt = now;
                heldSender = resolveSender(heldGift);
                showcaseUntil = 0L;
                catState = CAT_CARRY;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else {
                catState = CAT_LOOK;
                catLookStart = now;
                catLookEnd = now + 2600L + catRandom.nextInt(1500);
                catNextLookAt = now + 9000L + catRandom.nextInt(7000);
            }
        } else if (catState == CAT_CARRY && distance < dp(7)) {
            catState = CAT_SHOWCASE;
            showcaseUntil = now + 5400L;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } else if (catState == CAT_RETURN && distance < dp(9)) {
            heldGift = null;
            heldSender = null;
            catGift = null;
            catState = CAT_WANDER;
            chooseCatWanderTarget(now, acx, acy, avatarRadius, viewBottom);
            catNextLookAt = now + 14000L + catRandom.nextInt(9000);
        }
    }

    /** Once a day, Paimon offers one pinned gift to the owner of the profile. */
    private void maybeStartDailyGift(long now, float avatarRadius) {
        if (!canDrag || heldGift != null || catState != CAT_WANDER
                || gifts.isEmpty() || catAlpha < 0.65f
                || !DeltamentProfileMusic.isGiftOfDayEnabled()) {
            return;
        }
        if (draggingGift != null) {
            dailyCheckAt = now + 1500L;
            return;
        }
        if (now < dailyCheckAt) {
            return;
        }
        dailyCheckAt = now + 2000L;
        if (!shouldShowDailyGift()) {
            return;
        }
        startGiftOfDay(now, false);
    }


    private void drawCat(Canvas canvas, long now, float visibility) {
        if (catFlySheet == null || catHappySheet == null || catSurprisedSheet == null
                || catAlpha <= 0.01f || visibility <= 0.001f) {
            return;
        }

        final Bitmap sheet;
        final int frame;
        if (catState == CAT_SHOWCASE) {
            sheet = catHappySheet;
            frame = (int) ((Math.max(0L, now - heldAt) % CAT_EMOTION_MS)
                    * CAT_HAPPY_FRAMES / CAT_EMOTION_MS);
        } else if (catState == CAT_LOOK && catGift != null) {
            final long lookElapsed = Math.max(0L, now - catLookStart);
            if (lookElapsed < 700L) {
                sheet = catSurprisedSheet;
                frame = Math.min(CAT_SURPRISED_FRAMES - 1,
                        (int) (lookElapsed * CAT_SURPRISED_FRAMES / 900L));
            } else {
                sheet = catHappySheet;
                frame = (int) (((lookElapsed - 700L) % CAT_EMOTION_MS) * CAT_HAPPY_FRAMES / CAT_EMOTION_MS);
            }
        } else {
            sheet = catFlySheet;
            frame = (int) ((Math.max(0L, now - catFlyStart) % CAT_FLY_LOOP_MS)
                    * CAT_FLY_FRAMES / CAT_FLY_LOOP_MS);
        }

        final int cell = sheet.getWidth() / CAT_SHEET_COLS;
        if (cell <= 0) {
            return;
        }
        final int col = frame % CAT_SHEET_COLS;
        final int row = Math.min(sheet.getHeight() / cell - 1, frame / CAT_SHEET_COLS);
        catSrcRect.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell);

        final float size = dp(64) * (1f + 0.05f * catAttention)
                * (0.97f + 0.03f * (float) Math.sin(catBob * 2.1f));
        final float half = size / 2f;
        catDstRect.set(-half, -half, half, half);

        // Soft contact shadow keeps the pale model readable on light themes.
        canvas.save();
        canvas.translate(catX, catY + size * 0.38f);
        canvas.scale(1f, 0.34f);
        catGlowPaint.setShader(null);
        catGlowPaint.setColor(0x30000000);
        canvas.drawCircle(0f, 0f, size * 0.29f, catGlowPaint);
        canvas.restore();

        float flip = catFacing;
        if (Math.abs(flip) < 0.38f) {
            flip = flip < 0f ? -0.38f : 0.38f;
        }
        catPaint.setAlpha((int) (255f * catAlpha * visibility));
        catOutlinePaint.setAlpha((int) (170f * catAlpha * visibility));
        canvas.save();
        canvas.translate(catX, catY);
        canvas.rotate(catTilt);
        canvas.scale(flip * 1.055f, 1.055f);
        canvas.drawBitmap(sheet, catSrcRect, catDstRect, catOutlinePaint);
        canvas.restore();
        canvas.save();
        canvas.translate(catX, catY);
        canvas.rotate(catTilt);
        canvas.scale(flip, 1f);
        canvas.drawBitmap(sheet, catSrcRect, catDstRect, catPaint);
        canvas.restore();
    }

    private void drawCatHearts(Canvas canvas, long now, float visibility) {
        if (catGift == null || catGift == draggingGift || catGift == heldGift || catAttention < 0.01f) {
            return;
        }
        final float gx = catGift.drawX;
        final float gy = catGift.drawY;
        for (int i = 0; i < 3; i++) {
            final float t = (((now - catLookStart) / 1500f) + i / 3f) % 1f;
            final float x = lerp(gx, catX, 0.28f + 0.35f * t)
                    + (float) Math.sin((t * 2.4f + i) * Math.PI * 2f) * dp(5f);
            final float y = lerp(gy - dp(4), catY, 0.20f + 0.35f * t) - dp(15f) * t;
            final float alpha = catAttention * visibility * (float) Math.sin(Math.PI * t);
            drawHeart(canvas, x, y, dp(6.2f) * (0.82f + 0.28f * t), alpha * 0.9f);
        }
    }

    private void drawHeart(Canvas canvas, float x, float y, float size, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        catHeartPaint.setColor(0xFFFF7FB2);
        catHeartPaint.setAlpha((int) (255f * Math.min(1f, alpha)));
        catHeartPaint.setStyle(Paint.Style.FILL);
        catHeartPath.reset();
        catHeartPath.moveTo(0f, size * 0.42f);
        catHeartPath.cubicTo(-size * 0.95f, -size * 0.18f, -size * 0.42f, -size * 0.92f, 0f, -size * 0.32f);
        catHeartPath.cubicTo(size * 0.42f, -size * 0.92f, size * 0.95f, -size * 0.18f, 0f, size * 0.42f);
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(-10f);
        canvas.drawPath(catHeartPath, catHeartPaint);
        canvas.restore();
    }

    /** Fades the collectible card in/out with the showcase state. */
    private void updateCardAlpha(float dt) {
        final float target = catState == CAT_SHOWCASE && heldGift != null ? 1f : 0f;
        cardAlpha += (target - cardAlpha) * (1f - (float) Math.exp(-dt / 150f));
        if (cardAlpha < 0.002f) {
            cardAlpha = 0f;
        }
    }

    /** The gift Paimon is currently carrying, drawn as a child of the companion. */
    private void drawHeldGift(Canvas canvas, long now, float visibility) {
        if (heldGift == null) {
            return;
        }
        final float x = catX;
        final float y = catY + dp(24);
        float scale = catState == CAT_SHOWCASE ? 1.5f : 1.2f;
        if (catState == CAT_SHOWCASE) {
            scale *= 1f + 0.03f * (float) Math.sin(now / 260.0);
        }
        final float swing = (float) Math.sin(now / 200.0) * (catState == CAT_CARRY ? 7f : 3f);
        final float alpha = catAlpha * visibility;
        heldGift.draw(canvas, x, y, scale, swing, alpha, 1.35f, 0f, 0f);
    }

    private void drawGiftOfDayCard(Canvas canvas, float acx, float acy, float avatarRadius, float visibility) {
        if (heldGift == null || cardAlpha <= 0.002f || visibility <= 0.001f) {
            return;
        }
        final float maxW = Math.max(dp(180), getWidth() - dp(40));
        final float w = Math.min(dp(316), maxW);
        final float h = dp(96);
        float left = acx - w / 2f;
        float top = acy + avatarRadius + dp(38);
        final float limit = dragBottom() - dp(16) - h;
        if (top > limit) {
            top = limit;
        }
        if (top < dragTop() + dp(4)) {
            top = dragTop() + dp(4);
        }
        left = clamp(left, dp(12), Math.max(dp(12), getWidth() - dp(12) - w));
        cardRect.set(left, top, left + w, top + h);

        final float alpha = cardAlpha * visibility;
        canvas.save();
        canvas.translate(0f, (1f - cardAlpha) * dp(12));

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setShader(null);
        cardPath.reset();
        cardPath.addRoundRect(cardRect.left + dp(1), cardRect.top + dp(3),
                cardRect.right + dp(1), cardRect.bottom + dp(3), dp(15), dp(15), Path.Direction.CW);
        cardPaint.setColor(0x42000000);
        canvas.drawPath(cardPath, cardPaint);

        cardPath.reset();
        cardPath.addRoundRect(cardRect, dp(14), dp(14), Path.Direction.CW);
        cardPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite), 0.96f * alpha));
        canvas.drawPath(cardPath, cardPaint);

        cardAccentPaint.setStyle(Paint.Style.STROKE);
        cardAccentPaint.setStrokeWidth(dp(1.2f));
        cardAccentPaint.setColor(Theme.multAlpha(heldGift.color, 0.6f * alpha));
        canvas.drawPath(cardPath, cardAccentPaint);

        // Accent strip on the left edge.
        canvas.save();
        canvas.clipPath(cardPath);
        cardAccentPaint.setStyle(Paint.Style.FILL);
        cardAccentPaint.setColor(Theme.multAlpha(heldGift.color, 0.85f * alpha));
        canvas.drawRect(cardRect.left, cardRect.top, cardRect.left + dp(4), cardRect.bottom, cardAccentPaint);
        canvas.restore();

        final float x = cardRect.left + dp(15);
        final float right = cardRect.right - dp(12);

        cardSubPaint.setStyle(Paint.Style.FILL);
        cardSubPaint.setFakeBoldText(true);
        cardSubPaint.setTextSize(dp(9.5f));
        cardSubPaint.setLetterSpacing(0.16f);
        cardSubPaint.setColor(Theme.multAlpha(heldGift.color, 0.95f * alpha));
        canvas.drawText("ПОДАРОК ДНЯ", x, cardRect.top + dp(19), cardSubPaint);

        cardTextPaint.setStyle(Paint.Style.FILL);
        cardTextPaint.setFakeBoldText(true);
        cardTextPaint.setTextSize(dp(16));
        cardTextPaint.setColor(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), alpha));
        final String title = heldGift.title != null && heldGift.title.length() > 0
                ? heldGift.title : "Коллекционный подарок";
        canvas.drawText(ellipsize(cardTextPaint, title, right - x),
                x, cardRect.top + dp(43), cardTextPaint);

        cardSubPaint.setLetterSpacing(0f);
        cardSubPaint.setFakeBoldText(false);
        cardSubPaint.setTextSize(dp(12));
        cardSubPaint.setColor(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), alpha));
        final StringBuilder meta = new StringBuilder();
        if (heldGift.num > 0) {
            meta.append('№').append(heldGift.num);
        }
        if (heldGift.date > 0L) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(DateFormat.format("d MMM yyyy", heldGift.date * 1000L));
        }
        if (meta.length() == 0) {
            meta.append("из коллекции");
        }
        canvas.drawText(ellipsize(cardSubPaint, meta.toString(), right - x),
                x, cardRect.top + dp(63), cardSubPaint);

        final String sender = heldSender != null && heldSender.length() > 0
                ? "от " + heldSender : "Подарок дня";
        canvas.drawText(ellipsize(cardSubPaint, sender, right - x - dp(58)),
                x, cardRect.top + dp(81), cardSubPaint);

        cardSubPaint.setTextSize(dp(10));
        cardSubPaint.setColor(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.7f * alpha));
        final String hint = "тап — убрать";
        canvas.drawText(hint, right - cardSubPaint.measureText(hint),
                cardRect.top + dp(81), cardSubPaint);

        canvas.restore();
    }

    /** Truncates text with an ellipsis, because Paint has no setEllipsize. */
    private String ellipsize(Paint paint, String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) {
            return text;
        }
        final int len = paint.breakText(text, true, maxWidth - dp(10), null);
        return len > 0 ? text.substring(0, len) + "…" : "";
    }

    private void drawConstellationDim(Canvas canvas) {
        if (!consEdit) {
            return;
        }
        consUiPaint.setStyle(Paint.Style.FILL);
        consUiPaint.setShader(null);
        consUiPaint.setColor(0x3D000000);
        canvas.drawRect(0, 0, getWidth(), dragBottom(), consUiPaint);
    }

    /** Glowing links between the bubbles, drawn underneath them. */
    private void drawConstellations(Canvas canvas, long now, float visibility) {
        if (!DeltamentProfileMusic.isConstellationsEnabled() || visibility <= 0.001f) {
            return;
        }
        if (!constellations.isEmpty()) {
            final float twinkle = 0.78f + 0.22f * (float) Math.sin(now / 430.0);
            for (int i = 0; i < constellations.size(); i++) {
                final long[] link = constellations.get(i);
                final Gift a = findGiftById(link[0]);
                final Gift b = findGiftById(link[1]);
                if (a == null || b == null) {
                    continue;
                }
                drawLink(canvas,
                        giftCenterX(a), giftCenterY(a),
                        giftCenterX(b), giftCenterY(b),
                        a.color, b.color, visibility * twinkle);
            }
        }
        if (consEdit && consFrom != null) {
            drawLink(canvas,
                    giftCenterX(consFrom), giftCenterY(consFrom),
                    consTouchX, consTouchY,
                    consFrom.color, consFrom.color, visibility * 0.85f);
        }
    }

    private void drawLink(Canvas canvas, float x1, float y1, float x2, float y2,
                          int colorA, int colorB, float alpha) {
        final float dist = (float) Math.hypot(x2 - x1, y2 - y1);
        if (dist < dp(3) || alpha <= 0.01f) {
            return;
        }
        final float sag = Math.min(dp(46), dist * 0.22f);
        final float mx = (x1 + x2) / 2f;
        final float my = (y1 + y2) / 2f + sag;
        linkPath.reset();
        linkPath.moveTo(x1, y1);
        linkPath.quadTo(mx, my, x2, y2);

        linkGlowPaint.setStyle(Paint.Style.STROKE);
        linkGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        linkGlowPaint.setStrokeWidth(dp(4.4f));
        linkGlowPaint.setShader(null);
        linkGlowPaint.setColor(Theme.multAlpha(colorA, 0.30f * alpha));
        canvas.drawPath(linkPath, linkGlowPaint);

        linkPaint.setStyle(Paint.Style.STROKE);
        linkPaint.setStrokeCap(Paint.Cap.ROUND);
        linkPaint.setStrokeWidth(dp(1.5f));
        linkPaint.setShader(new LinearGradient(x1, y1, x2, y2,
                new int[]{Theme.multAlpha(colorA, 0.92f * alpha),
                        Theme.multAlpha(colorB, 0.92f * alpha)},
                null, Shader.TileMode.CLAMP));
        canvas.drawPath(linkPath, linkPaint);
        linkPaint.setShader(null);

        consUiPaint.setStyle(Paint.Style.FILL);
        consUiPaint.setShader(null);
        consUiPaint.setColor(Theme.multAlpha(colorA, 0.85f * alpha));
        canvas.drawCircle(x1, y1, dp(2.1f), consUiPaint);
        consUiPaint.setColor(Theme.multAlpha(colorB, 0.85f * alpha));
        canvas.drawCircle(x2, y2, dp(2.1f), consUiPaint);
    }

    private void updateConsStarPath(float r) {
        consStarPath.reset();
        for (int i = 0; i < 10; i++) {
            final float rr = (i & 1) == 0 ? r : r * 0.45f;
            final double a = -Math.PI / 2.0 + i * Math.PI / 5.0;
            final float px = (float) Math.cos(a) * rr;
            final float py = (float) Math.sin(a) * rr;
            if (i == 0) {
                consStarPath.moveTo(px, py);
            } else {
                consStarPath.lineTo(px, py);
            }
        }
        consStarPath.close();
    }

    /** The star button that enters the constellation editor, plus its hints. */
    private void drawConstellationButton(Canvas canvas, long now, float visibility) {
        consButtonVisible = false;
        if (!DeltamentProfileMusic.isConstellationsEnabled() || !canDrag
                || gifts.size() < 2 || heldGift != null || flingSpinStart >= 0L
                || visibility <= 0.001f) {
            if (heldGift != null || flingSpinStart >= 0L) {
                consEdit = false;
                consFrom = null;
            }
            return;
        }
        consButtonVisible = true;
        final float bx = getWidth() - dp(27);
        final float by = dragBottom() - dp(40);
        consButtonX = bx;
        consButtonY = by;
        consButtonR = dp(19);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        final float alpha = visibility;

        consUiPaint.setShader(null);
        consUiPaint.setStyle(Paint.Style.FILL);
        consUiPaint.setColor(Theme.multAlpha(bg, 0.86f * alpha));
        canvas.drawCircle(bx, by, consButtonR, consUiPaint);

        consUiPaint.setStyle(Paint.Style.STROKE);
        consUiPaint.setStrokeWidth(dp(1.2f));
        consUiPaint.setColor(Theme.multAlpha(accent, (consEdit ? 1f : 0.62f) * alpha));
        canvas.drawCircle(bx, by, consButtonR, consUiPaint);

        if (!consEdit) {
            final float pulse = 0.5f + 0.5f * (float) Math.sin(now / 480.0);
            consUiPaint.setStyle(Paint.Style.STROKE);
            consUiPaint.setStrokeWidth(dp(1f));
            consUiPaint.setColor(Theme.multAlpha(accent, 0.20f * alpha * (0.4f + pulse)));
            canvas.drawCircle(bx, by, consButtonR + dp(4) + dp(3) * pulse, consUiPaint);
        }

        updateConsStarPath(dp(9f));
        canvas.save();
        canvas.translate(bx, by);
        consUiPaint.setStyle(Paint.Style.FILL);
        consUiPaint.setColor(Theme.multAlpha(consEdit ? 0xFFFFFFFF : accent, alpha));
        canvas.drawPath(consStarPath, consUiPaint);
        canvas.restore();

        if (!consEdit && constellations.isEmpty()) {
            if (consHintStart == 0L) {
                consHintStart = now;
            }
            final long age = now - consHintStart;
            if (age < 6800L && !consHintShown) {
                final float hintAlpha = alpha * Math.min(1f, Math.max(0f, (6800f - age) / 900f));
                consTextPaint.setStyle(Paint.Style.FILL);
                consTextPaint.setFakeBoldText(false);
                consTextPaint.setTextSize(dp(11));
                consTextPaint.setColor(Theme.multAlpha(
                        Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.85f * hintAlpha));
                final String text = "Созвездия";
                final float tw = consTextPaint.measureText(text);
                final float tx = bx - consButtonR - dp(8) - tw;
                final float ty = by + dp(4);
                consUiPaint.setStyle(Paint.Style.FILL);
                consUiPaint.setColor(Theme.multAlpha(bg, 0.76f * hintAlpha));
                canvas.drawRoundRect(tx - dp(6), ty - dp(11), tx + tw + dp(6), ty + dp(4),
                        dp(8), dp(8), consUiPaint);
                canvas.drawText(text, tx, ty, consTextPaint);
                if (age > 6400L) {
                    consHintShown = true;
                }
            }
        }

        if (consEdit) {
            consTextPaint.setStyle(Paint.Style.FILL);
            consTextPaint.setFakeBoldText(false);
            consTextPaint.setTextSize(dp(11));
            consTextPaint.setColor(Theme.multAlpha(0xFFFFFFFF, 0.9f * alpha));
            final String hint = "Проведи между пузырями · тап по линии — убрать";
            final float tw = consTextPaint.measureText(hint);
            canvas.drawText(hint, Math.max(dp(8), (getWidth() - tw) / 2f),
                    dragBottom() - dp(70), consTextPaint);
        }
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext == null ? null
                : ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String positionKey(long giftId) {
        return currentAccount + "_" + dialogId + "_" + giftId;
    }

    private void loadPosition(Gift gift) {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        final String value = prefs.getString(positionKey(gift.id), null);
        if (value == null) {
            return;
        }
        final int comma = value.indexOf(',');
        if (comma <= 0) {
            return;
        }
        try {
            final float x = Float.parseFloat(value.substring(0, comma));
            final float y = Float.parseFloat(value.substring(comma + 1));
            if (!Float.isNaN(x) && !Float.isInfinite(x) && !Float.isNaN(y) && !Float.isInfinite(y)) {
                // Sanity clamp: a corrupt/infinite offset used to push every
                // bubble off-screen and made the whole collection disappear.
                gift.offsetX = Math.max(-6f, Math.min(6f, x));
                gift.offsetY = Math.max(-6f, Math.min(6f, y));
            } else {
                prefs.edit().remove(positionKey(gift.id)).apply();
            }
        } catch (NumberFormatException ignore) {
        }
    }

    private void savePosition(Gift gift) {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(positionKey(gift.id), gift.offsetX + "," + gift.offsetY)
                .apply();
    }

    // ---- "Живые созвездия": user-drawn links between gift bubbles ----------

    private String linksKey() {
        return currentAccount + "_" + dialogId + "_constellations";
    }

    private String dailyKey() {
        return currentAccount + "_" + dialogId + "_daily_gift";
    }

    private void ensureConstellationsLoaded() {
        if (constellationsLoaded) {
            return;
        }
        constellationsLoaded = true;
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        final String value = prefs.getString(linksKey(), null);
        if (value == null || value.length() == 0) {
            return;
        }
        for (String part : value.split(";")) {
            final int colon = part.indexOf(':');
            if (colon <= 0 || colon >= part.length() - 1) {
                continue;
            }
            try {
                final long a = Long.parseLong(part.substring(0, colon));
                final long b = Long.parseLong(part.substring(colon + 1));
                if (a != 0L && b != 0L && a != b) {
                    constellations.add(new long[]{a, b});
                }
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private void saveConstellations() {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < constellations.size(); i++) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(constellations.get(i)[0]).append(':').append(constellations.get(i)[1]);
        }
        prefs.edit().putString(linksKey(), builder.toString()).apply();
    }

    private Gift findGiftById(long id) {
        for (int i = 0; i < gifts.size(); i++) {
            if (gifts.get(i).id == id) {
                return gifts.get(i);
            }
        }
        return null;
    }

    private void toggleLink(long a, long b) {
        if (a == b || a == 0L || b == 0L) {
            return;
        }
        for (int i = 0; i < constellations.size(); i++) {
            final long[] link = constellations.get(i);
            if ((link[0] == a && link[1] == b) || (link[0] == b && link[1] == a)) {
                constellations.remove(i);
                saveConstellations();
                return;
            }
        }
        if (constellations.size() >= 24) {
            return;
        }
        constellations.add(new long[]{a, b});
        saveConstellations();
    }

    /** Index of the constellation line under the touch, or -1. */
    private int linkUnder(float x, float y) {
        final float threshold = dp(13);
        for (int i = 0; i < constellations.size(); i++) {
            final long[] link = constellations.get(i);
            final Gift a = findGiftById(link[0]);
            final Gift b = findGiftById(link[1]);
            if (a == null || b == null) {
                continue;
            }
            final float x1 = giftCenterX(a), y1 = giftCenterY(a);
            final float x2 = giftCenterX(b), y2 = giftCenterY(b);
            final float dist = (float) Math.hypot(x2 - x1, y2 - y1);
            final float sag = Math.min(dp(46), dist * 0.22f);
            final float mx = (x1 + x2) / 2f;
            final float my = (y1 + y2) / 2f + sag;
            for (int step = 1; step < 8; step++) {
                final float t = step / 8f;
                final float inv = 1f - t;
                final float px = inv * inv * x1 + 2f * inv * t * mx + t * t * x2;
                final float py = inv * inv * y1 + 2f * inv * t * my + t * t * y2;
                if (Math.hypot(px - x, py - y) <= threshold) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ---- "Мяууу-почтальон" / "Подарок дня" --------------------------------

    private boolean shouldShowDailyGift() {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return true;
        }
        final long last = prefs.getLong(dailyKey(), 0L);
        // The repeat interval is user-configurable (minutes), default 12 h.
        final long interval = DeltamentProfileMusic.getGiftOfDayIntervalMinutes() * 60_000L;
        return last <= 0L || Math.abs(System.currentTimeMillis() - last) >= interval;
    }

    private void markDailyShown() {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putLong(dailyKey(), System.currentTimeMillis()).apply();
    }

    /** Deterministic rotation over the pinned gifts, so every day is different. */
    private Gift pickDailyGift() {
        final ArrayList<Gift> pinned = new ArrayList<>();
        final ArrayList<Gift> pool = new ArrayList<>();
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            if (gift == draggingGift || gift == heldGift) {
                continue;
            }
            pool.add(gift);
            if (gift.pinned) {
                pinned.add(gift);
            }
        }
        final ArrayList<Gift> source = pinned.isEmpty() ? pool : pinned;
        if (source.isEmpty()) {
            return null;
        }
        final long day = System.currentTimeMillis() / 86400000L;
        return source.get((int) (day % source.size()));
    }

    private String resolveSender(Gift gift) {
        if (gift.fromId != 0L) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(gift.fromId);
            if (user != null) {
                final String name = UserObject.getUserName(user);
                if (name != null && name.length() > 0) {
                    return name;
                }
            }
        }
        if (gift.ownerName != null && gift.ownerName.length() > 0) {
            return gift.ownerName;
        }
        return null;
    }

    private void startGiftOfDay(long now, boolean manual) {
        if (heldGift != null || gifts.isEmpty() || !DeltamentProfileMusic.isCatEnabled()
                || !DeltamentProfileMusic.isGiftOfDayEnabled()) {
            return;
        }
        if (catState == CAT_CARRY || catState == CAT_SHOWCASE || catState == CAT_RETURN) {
            return;
        }
        if (manual && now - dailyAttemptAt < 1200L) {
            return;
        }
        final Gift gift = pickDailyGift();
        if (gift == null) {
            return;
        }
        dailyAttemptAt = now;
        catGift = gift;
        pendingCarry = true;
        catState = CAT_TO_GIFT;
        catNextLookAt = now + 90000L;
        if (!manual) {
            markDailyShown();
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    private void triggerGiftOfDay() {
        startGiftOfDay(SystemClock.uptimeMillis(), true);
    }

    private void dismissGiftOfDay() {
        if (heldGift == null) {
            return;
        }
        catState = CAT_RETURN;
        showcaseUntil = 0L;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    /**
     * Aborts the ritual without any animation: the gift returns to being a
     * normal bubble and Paimon keeps flying from where she currently is, so a
     * cancelled ritual never teleports her across the screen.
     */
    private void cancelGiftOfDay() {
        heldGift = null;
        heldSender = null;
        pendingCarry = false;
        showcaseUntil = 0L;
        cardAlpha = 0f;
        catGift = null;
        if (catState != CAT_WANDER) {
            catState = CAT_WANDER;
            catRetargetAt = 0L;
            catNextLookAt = SystemClock.uptimeMillis() + 12000L;
        }
    }

    public final class Gift {

        public final long id;
        /** Base gift id of a collectible, used to find the generic artwork. */
        private final long giftId;
        public TLRPC.Document document;
        public long documentId;
        public final int color;
        public final String slug;
        private StarsReactionsSheet.Particles particles;
        private boolean documentResolved;

        public int position = -1;
        /** Pinned-to-top gifts are the ones the "Мяууу" companion prefers. */
        public boolean pinned;

        /** Collectible card data: title, serial number, date and sender. */
        public String title = "";
        public int num;
        public long date;
        public long fromId;
        public String ownerName;

        public void applySavedGift(TL_stars.SavedStarGift saved) {
            if (saved == null) {
                return;
            }
            date = saved.date;
            fromId = peerUserId(saved.from_id);
            if (ownerName == null && saved.name_hidden) {
                ownerName = "";
            }
        }

        private long peerUserId(TLRPC.Peer peer) {
            if (peer instanceof TLRPC.TL_peerUser) {
                return ((TLRPC.TL_peerUser) peer).user_id;
            }
            return 0L;
        }

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            giftId = gift.gift_id;
            title = gift.title == null ? "" : gift.title;
            num = gift.num;
            TLRPC.Document doc = gift.getDocument();
            if (doc != null && doc.id == 0) {
                doc = null;
            }
            document = doc;
            documentId = doc == null ? 0 : doc.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            color = backdrop.center_color | 0xFF000000;
            slug = gift.slug;
            initParticles();
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            giftId = 0;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            slug = status.slug;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 5);
            final float gsz = dp(46);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public ImageReceiver imageReceiver;
        public AnimatedFloat animatedFloat;

        public final RectF bounds = new RectF();
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        /** User offset from the default slot position, normalised to the avatar box. */
        public float offsetX, offsetY;
        /** Default slot position of the last draw, without the user offset. */
        public float baseCx, baseCy;
        /** Last drawn center, including the user offset. */
        public float lastCx, lastCy;

        /** Glide back to the layout after the user releases the bubble. */
        public long settleStart;
        public float settleFromX, settleFromY, settleFromScale = 1f;
        /**
         * After a drop the bubble ignores the collision displacement for a
         * moment, so it visibly stays exactly where it was released instead of
         * being pushed around by its neighbours.
         */
        public long holdUntil;

        /** Physical displacement from the layout slot, in pixels. */
        public float physX, physY;
        /** Physical velocity of the displacement, in pixels per second. */
        public float velX, velY;
        /** Position computed for the current frame, before the physics offset. */
        public float drawX, drawY;
        public float drawScale = 1f, drawAlpha, drawGlow, drawFront, drawWiggle;

        /** Orbit slot around the avatar, in the style of Telegram for iOS. */
        public float orbitDistance, orbitAngle, orbitScale = 1f;
        /** Idle float/wiggle/glow animation parameters for this bubble. */
        public float idlePhase, hoverDuration = 4f, wiggleDuration = 2.5f, glowDuration = 2.5f;

        public void copy(Gift b) {
            gradient = b.gradient;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
            pinned = b.pinned;
            title = b.title;
            num = b.num;
            date = b.date;
            fromId = b.fromId;
            ownerName = b.ownerName;
            offsetX = b.offsetX;
            offsetY = b.offsetY;
            orbitDistance = b.orbitDistance;
            orbitAngle = b.orbitAngle;
            orbitScale = b.orbitScale;
            idlePhase = b.idlePhase;
            hoverDuration = b.hoverDuration;
            wiggleDuration = b.wiggleDuration;
            glowDuration = b.glowDuration;
            if (documentId == 0 && b.documentId != 0) {
                // Keep the artwork that was already resolved for this gift.
                document = b.document;
                documentId = b.documentId;
                documentResolved = true;
            }
            if (b.imageReceiver != null && b.documentId == documentId) {
                // Reuse the already running sticker animation instead of
                // recreating an ImageReceiver on every list refresh.
                imageReceiver = b.imageReceiver;
                imageReceiver.setParentView(ProfileGiftsView.this);
            } else if (b.imageReceiver != null) {
                b.imageReceiver.onDetachedFromWindow();
                b.imageReceiver = null;
            }
        }

        /** Starts loading the gift artwork. Models are lottie documents on this server. */
        public void ensureImageReceiver() {
            if (imageReceiver != null || document == null || document.id == 0) {
                return;
            }
            imageReceiver = new ImageReceiver(ProfileGiftsView.this);
            setGiftImage(imageReceiver, document, 160);
            if (isAttachedToWindow()) {
                imageReceiver.onAttachedToWindow();
            }
        }

        /**
         * Some saved gifts arrive without a model attribute document. Fall back
         * to the generic gift artwork of the same base gift, so the bubble never
         * ends up as an empty coloured circle.
         */
        public void resolveDocument() {
            if (documentId != 0 || documentResolved || giftId == 0) {
                return;
            }
            documentResolved = true;
            final StarsController starsController = StarsController.getInstance(currentAccount);
            final TL_stars.StarGift base = starsController.getStarGift(giftId);
            if (base != null && base.sticker != null) {
                applyDocument(base.sticker);
            } else {
                starsController.getStarGift(giftId, gift -> {
                    if (gift != null) {
                        applyDocument(gift.sticker);
                    }
                });
            }
        }

        private void applyDocument(TLRPC.Document doc) {
            if (doc == null || doc.id == 0 || documentId != 0) {
                return;
            }
            document = doc;
            documentId = doc.id;
            ensureImageReceiver();
            invalidate();
        }

        public void release() {
            if (imageReceiver != null) {
                imageReceiver.onDetachedFromWindow();
                imageReceiver = null;
            }
            gradient = null;
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float glowAlpha,
                float stretch, float stretchAngle
        ) {
            if (alpha <= 0.0f) return;
            final float d = dp(54);
            final float hit = dp(60);
            bounds.set(cx - hit / 2f, cy - hit / 2f, cx + hit / 2f, cy + hit / 2f);

            final int skin = DeltamentBubbleSkins.get();
            final boolean defaultSkin = DeltamentBubbleSkins.isDefault(skin);
            final int accent = defaultSkin ? color : DeltamentBubbleSkins.accent(skin);
            final float radius = d / 2f;
            final boolean dark = Theme.isCurrentThemeDark();

            ensureSphereShaders(radius, accent, dark);

            canvas.save();
            canvas.translate(cx, cy);
            // Squash/stretch along the motion direction: it sells the illusion
            // of a soft, floating object instead of a flat sticker.
            if (stretch > 0.001f) {
                canvas.rotate(stretchAngle);
                canvas.scale(1f + stretch, 1f - stretch * 0.62f);
                canvas.rotate(-stretchAngle);
            }
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);

            // Tinted halo: a wide, very soft coloured bloom behind the sphere.
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) ((defaultSkin ? 0x4A : 0x66) * alpha * glowAlpha));
                canvas.drawCircle(0, 0, radius * 1.62f, gradientPaint);
            }

            // Contact shadow: the ball floats a few pixels above the surface.
            canvas.save();
            canvas.translate(radius * 0.05f, radius * 0.62f);
            canvas.scale(1.0f, 0.42f);
            bubbleFillPaint.setStyle(Paint.Style.FILL);
            bubbleFillPaint.setShader(sphereShadowShader);
            bubbleFillPaint.setAlpha((int) (0xB8 * alpha));
            canvas.drawCircle(0, 0, radius * 1.16f, bubbleFillPaint);
            canvas.restore();

            // Glass body: a coloured volume lit from the upper left.
            bubbleFillPaint.setShader(sphereBodyShader);
            bubbleFillPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawCircle(0, 0, radius, bubbleFillPaint);

            // Occlusion at the rim plus shadow on the lower half: this is what
            // makes the sphere read as a sphere instead of a flat disc.
            bubbleFillPaint.setShader(sphereInnerShader);
            canvas.drawCircle(0, 0, radius, bubbleFillPaint);
            bubbleFillPaint.setShader(sphereAOShader);
            canvas.drawCircle(0, 0, radius, bubbleFillPaint);
            bubbleFillPaint.setShader(null);

            // Bright rim running along the lit edge.
            final float rimRadius = radius - dp(0.7f);
            bubbleRimPaint.setStyle(Paint.Style.STROKE);
            bubbleRimPaint.setStrokeWidth(dp(1.35f));
            bubbleRimPaint.setShader(sphereRimShader);
            bubbleRimPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawCircle(0, 0, rimRadius, bubbleRimPaint);
            bubbleRimPaint.setShader(null);

            // Thick-glass crescent on the upper left.
            bubbleRimRect.set(-rimRadius, -rimRadius, rimRadius, rimRadius);
            bubbleRimPaint.setColor(0xFFFFFFFF);
            bubbleRimPaint.setStrokeWidth(dp(1.7f));
            bubbleRimPaint.setAlpha((int) ((dark ? 0.92f : 0.55f) * 0xFF * alpha));
            canvas.drawArc(bubbleRimRect, 196f, 112f, false, bubbleRimPaint);

            // Accent caustic focused on the bottom inner edge.
            final float causticRadius = radius * 0.885f;
            bubbleRimRect.set(-causticRadius, -causticRadius, causticRadius, causticRadius);
            bubbleRimPaint.setColor(accent);
            bubbleRimPaint.setStrokeWidth(dp(2.6f));
            bubbleRimPaint.setAlpha((int) ((dark ? 0.72f : 0.46f) * 0xFF * alpha));
            canvas.drawArc(bubbleRimRect, 22f, 136f, false, bubbleRimPaint);

            // Soap-film iridescence, kept very faint.
            final float filmRadius = radius * 0.955f;
            bubbleRimRect.set(-filmRadius, -filmRadius, filmRadius, filmRadius);
            bubbleRimPaint.setStrokeWidth(dp(1.5f));
            bubbleRimPaint.setColor(0xFF8FE8FF);
            bubbleRimPaint.setAlpha((int) (0.30f * 0xFF * alpha));
            canvas.drawArc(bubbleRimRect, 200f, 44f, false, bubbleRimPaint);
            bubbleRimPaint.setColor(0xFFFF9BE0);
            bubbleRimPaint.setAlpha((int) (0.24f * 0xFF * alpha));
            canvas.drawArc(bubbleRimRect, 16f, 40f, false, bubbleRimPaint);

            // Coloured skins add their own ring and bloom.
            if (!defaultSkin) {
                skinGlowPaint.setStyle(Paint.Style.STROKE);
                skinGlowPaint.setStrokeWidth(dp(5f));
                skinGlowPaint.setColor(accent);
                skinGlowPaint.setAlpha((int) (64 * alpha));
                canvas.drawCircle(0, 0, radius, skinGlowPaint);
                skinRingPaint.setStyle(Paint.Style.STROKE);
                skinRingPaint.setStrokeWidth(dp(DeltamentBubbleSkins.ringWidth(skin)));
                skinRingPaint.setColor(accent);
                skinRingPaint.setAlpha((int) (225 * alpha));
                canvas.drawCircle(0, 0, radius - dp(1f), skinRingPaint);
            }

            particles.process();
            particles.draw(canvas, dark ? 0xFFFFFFFF : accent, alpha);

            // Gift artwork, drawn after the glass so it sits inside the ball.
            if (imageReceiver != null) {
                final float sz = dp(30);
                imageReceiver.setImageCoords(-sz / 2f, -sz / 2f, sz, sz);
                imageReceiver.setAlpha(alpha);
                imageReceiver.draw(canvas);
            } else {
                bubbleGlyphPaint.setStyle(Paint.Style.FILL);
                bubbleGlyphPaint.setColor(dark ? 0xFFFFFFFF : 0xFF000000);
                bubbleGlyphPaint.setAlpha((int) (0.7f * alpha * 0xFF));
                bubbleGlyphPaint.setTextSize(dp(22));
                bubbleGlyphPaint.setTextAlign(Paint.Align.CENTER);
                final Paint.FontMetrics fm = bubbleGlyphPaint.getFontMetrics();
                canvas.drawText("\u2605", 0, -(fm.ascent + fm.descent) / 2f, bubbleGlyphPaint);
            }

            // Specular: soft sheen on the glass surface plus a hard nucleus.
            canvas.save();
            canvas.translate(-radius * 0.34f, -radius * 0.42f);
            canvas.rotate(-34f);
            canvas.scale(1f, 0.66f);
            bubbleHighlightPaint.setStyle(Paint.Style.FILL);
            bubbleHighlightPaint.setShader(sphereSpecularShader);
            bubbleHighlightPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawCircle(0, 0, radius * 0.38f, bubbleHighlightPaint);
            bubbleHighlightPaint.setShader(null);
            canvas.restore();

            bubbleHighlightPaint.setColor(0xFFFFFFFF);
            bubbleHighlightPaint.setAlpha((int) (0xE0 * alpha));
            canvas.drawCircle(-radius * 0.45f, -radius * 0.54f, radius * 0.055f, bubbleHighlightPaint);
            bubbleHighlightPaint.setAlpha((int) (0x50 * alpha));
            canvas.drawCircle(-radius * 0.40f, -radius * 0.50f, radius * 0.11f, bubbleHighlightPaint);

            canvas.restore();
        }

        private Shader sphereBodyShader, sphereInnerShader, sphereAOShader, sphereRimShader, sphereSpecularShader, sphereShadowShader;
        private float sphereShaderRadius = -1f;
        private int sphereShaderAccent;
        private boolean sphereShaderDark;

        /** Builds (and caches) the multi-layer shading used by the 3D bubble. */
        private void ensureSphereShaders(float r, int accent, boolean dark) {
            if (sphereBodyShader != null && Math.abs(sphereShaderRadius - r) < 0.5f
                    && sphereShaderAccent == accent && sphereShaderDark == dark) {
                return;
            }
            sphereShaderRadius = r;
            sphereShaderAccent = accent;
            sphereShaderDark = dark;

            // Lit glass volume: hot core towards the light, saturated colour
            // through the middle, thick dark glass on the far edge.
            sphereBodyShader = new RadialGradient(
                    -r * 0.34f, -r * 0.42f, r * 1.60f,
                    new int[]{
                            0x86FFFFFF,
                            Theme.multAlpha(accent, dark ? 0.52f : 0.38f),
                            Theme.multAlpha(accent, dark ? 0.28f : 0.18f),
                            Theme.multAlpha(accent, dark ? 0.56f : 0.34f),
                            dark ? 0x70000000 : 0x4A000000
                    },
                    new float[]{0f, 0.25f, 0.60f, 0.87f, 1f},
                    Shader.TileMode.CLAMP
            );
            // Ambient occlusion around the whole rim.
            sphereInnerShader = new RadialGradient(
                    0, 0, r * 1.02f,
                    new int[]{0x00000000, 0x00000000, dark ? 0x60000000 : 0x44000000},
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP
            );
            // Extra shadow on the lower half, where the light does not reach.
            sphereAOShader = new LinearGradient(
                    0, -r * 0.15f, 0, r,
                    new int[]{0x00000000, 0x00000000, dark ? 0x5E000000 : 0x3C000000},
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP
            );
            // Rim light: bright towards the upper left, vanishing bottom right.
            sphereRimShader = new LinearGradient(
                    -r * 0.85f, -r, r * 0.85f, r,
                    new int[]{0xFFFFFFFF, 0x7AFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.42f, 0.76f, 1f},
                    Shader.TileMode.CLAMP
            );
            sphereSpecularShader = new RadialGradient(
                    0, 0, r * 0.36f,
                    new int[]{0xCCFFFFFF, 0x5AFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            sphereShadowShader = new RadialGradient(
                    0, 0, r * 1.24f,
                    new int[]{dark ? 0x78000000 : 0x54000000, 0x1A000000, 0x00000000},
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP
            );
        }
    }

    private StarsController.GiftsList list;

    public final ArrayList<Gift> oldGifts = new ArrayList<>();
    public final ArrayList<Gift> gifts = new ArrayList<>();
    public final HashSet<Long> giftIds = new HashSet<>();
    public int maxCount;

    public void update() {
        // DeltaGram: keep the bubbles independent from the server-side
        // "gifts in profile" flag. On this server the flag is not always
        // present, and a stale/false value used to hide the whole collection.
        if (draggingGift != null) {
            finishDrag(true);
        }

        maxCount = MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit;
        if (maxCount <= 0) {
            // A missing/zero server value used to produce an empty bubble list.
            maxCount = 6;
        }
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        final TLRPC.EmojiStatus emojiStatus;
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            emojiStatus = user == null ? null : user.emoji_status;
        } else {
            final TLRPC.User chat = MessagesController.getInstance(currentAccount).getUser(-dialogId);
            emojiStatus = chat == null ? null : chat.emoji_status;
        }
        if (emojiStatus instanceof TLRPC.TL_emojiStatusCollectible) {
            giftIds.add(((TLRPC.TL_emojiStatusCollectible) emojiStatus).collectible_id);
        }
        list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        if (list != null) {
            for (int i = 0; i < list.gifts.size(); i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.pinned_to_top && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    gift.pinned = true;
                    gift.applySavedGift(savedGift);
                    if (!giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
            // DeltaGram: every collectible gift becomes a bubble, on any
            // profile. Only the owner can drag them, but viewers should still
            // see the collection instead of an empty header. Pinned gifts keep
            // their priority (they are added first above).
            for (int i = 0; i < list.gifts.size() && gifts.size() < maxCount; i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    gift.applySavedGift(savedGift);
                    if (!giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
        }

        boolean changed = false;
        if (gifts.size() != oldGifts.size()) {
            changed = true;
        } else for (int i = 0; i < gifts.size(); i++) {
            if (!gifts.get(i).equals(oldGifts.get(i))) {
                changed = true;
                break;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            Gift oldGift = null;
            for (int j = 0; j < oldGifts.size(); ++j) {
                if (oldGifts.get(j).id == g.id) {
                    oldGift = oldGifts.get(j);
                    break;
                }
            }

            if (oldGift != null) {
                g.copy(oldGift);
            } else {
                // Soft halo behind the glass bubble, using the gift's own
                // backdrop colour (the same source iOS uses for its glow).
                g.gradient = new RadialGradient(0, 0, dp(34), new int[]{g.color, Theme.multAlpha(g.color, 0.0f)}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                g.gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.gradientPaint.setShader(g.gradient);
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(0.0f);
                // Idle float parameters, staggered like the iOS cover does.
                g.idlePhase = (gifts.indexOf(g) * 0.37f) + bubbleRandom.nextFloat() * 2.5f;
                g.hoverDuration = 3.5f + bubbleRandom.nextFloat();
                g.wiggleDuration = 2f + bubbleRandom.nextFloat();
                g.glowDuration = 2f + bubbleRandom.nextFloat();
                loadPosition(g);
            }
            g.resolveDocument();
            g.ensureImageReceiver();
        }

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            positions.add(i);
        }

        for (int i = 0; i < oldGifts.size(); i++) {
            final Gift g = oldGifts.get(i);
            Gift newGift = null;
            for (int j = 0; j < gifts.size(); ++j) {
                if (gifts.get(j).id == g.id) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift == null) {
                g.release();
            } else {
                positions.remove((Object) g.position);
            }
        }

        if (!positions.isEmpty()) {
            BagRandomizer<Integer> bagRandomizer = new BagRandomizer<>(positions);
            for (int i = 0; i < gifts.size(); ++i) {
                final Gift g = gifts.get(i);
                if (g.position == -1) {
                    g.position = bagRandomizer.next();
                }
            }
        }

        if (changed || positionsWidth != getWidth() || positionsHeight != getHeight()) {
            positionsDirty = true;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (!active) return;

        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = avatarContainer.getWidth() * avatarContainer.getScaleX();
        final float realH = avatarContainer.getHeight() * avatarContainer.getScaleY();
        final float barBottom = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
        final float acx = realW > 0f ? realX + realW / 2f : getWidth() / 2f;
        final float acy = realH > 0f ? realY + realH / 2f : barBottom + dp(54);
        final float avatarRadius = realW > 0f && realH > 0f
                ? Math.min(realW, realH) / 2f
                : dp(48);
        final float viewBottom = Math.max(getHeight(), barBottom + dp(240));

        final float visibility = (1f - 0.92f * expandProgress) * (1f - actionBarProgress);

        final long now = SystemClock.uptimeMillis();
        final float dt = lastFrameTime == 0L ? 16f : Math.min(50f, Math.max(1f, now - lastFrameTime));
        lastFrameTime = now;

        updateMusicPulse(dt);
        updateCardAlpha(dt);
        ensureConstellationsLoaded();
        final boolean musicVisible = visibility > 0.001f
                && (musicKick > 0.01f || musicLevel > 0.01f);
        final boolean catOn = DeltamentProfileMusic.isCatEnabled();
        if (!catOn && (catFlySheet != null || catGift != null || catAlpha > 0f || heldGift != null)) {
            cancelGiftOfDay();
            catGift = null;
            catState = CAT_WANDER;
            catAttention = 0f;
            catAlpha = 0f;
            catInitialized = false;
            releaseCat();
            catLoadFailed = false;
        }

        if (gifts.isEmpty()) {
            // A musical profile should still breathe when there is nothing to
            // orbit, and "Мяууу" still flies around the avatar.
            final boolean showCat = catOn && visibility > 0.001f;
            if (showCat) {
                updateCat(dt, now, acx, acy, avatarRadius, viewBottom, visibility);
            }
            if (musicVisible || showCat) {
                canvas.save();
                canvas.clipRect(0, 0, getWidth(), viewBottom);
                if (musicVisible) {
                    drawMusicVisualizer(canvas, acx, acy, avatarRadius, visibility);
                }
                if (showCat) {
                    drawCat(canvas, now, visibility);
                }
                canvas.restore();
                if (isAttachedToWindow()) {
                    postInvalidateOnAnimation();
                }
            }
            return;
        }
        if (visibility <= 0.001f) return;

        final float avatarSize = dp(96);
        // Slots are stored as angle + distance around the avatar, so a moving
        // avatar (parallax, pull-down) just drags the whole constellation with
        // it instead of re-randomising the layout mid-gesture.
        if (positionsDirty || positionsWidth != getWidth() || positionsHeight != getHeight()
                || Math.abs(positionsExpandY - viewBottom) > 0.5f) {
            computePositions(acx, acy, avatarSize, viewBottom);
        }

        lastAvatarWidth = avatarSize;
        lastAvatarHeight = avatarSize;

        // Elastic follow of the dragged bubble: the sphere trails the finger a
        // little instead of being glued to it.
        if (draggingGift != null) {
            final float follow = 1f - (float) Math.exp(-dt / 55f);
            dragRenderX += (dragCx - dragRenderX) * follow;
            dragRenderY += (dragCy - dragRenderY) * follow;
            dragLift += (1.22f - dragLift) * (1f - (float) Math.exp(-dt / 70f));
            final float targetStretch = Math.min(0.17f,
                    (float) Math.hypot(dragVelX, dragVelY) / Math.max(0.01f, dpf2(4.2f)));
            dragStretch += (targetStretch - dragStretch) * (1f - (float) Math.exp(-dt / 70f));
            if (dragVelX * dragVelX + dragVelY * dragVelY > 0.0001f) {
                dragStretchAngle = (float) Math.toDegrees(Math.atan2(dragVelY, dragVelX));
            }
            // Holding the bubble almost still makes the other spheres swirl
            // around the finger: the longer the hold, the stronger the vortex.
            final float fingerSpeed = (float) Math.hypot(dragVelX, dragVelY);
            if (fingerSpeed < 0.35f) {
                dragHoldMs = Math.min(2400f, dragHoldMs + dt);
            } else {
                dragHoldMs = Math.max(0f, dragHoldMs - dt * 1.8f);
            }
            dragVortex += (Math.min(1f, dragHoldMs / 850f) - dragVortex)
                    * (1f - (float) Math.exp(-dt / 120f));
        } else {
            dragStretch *= (float) Math.exp(-dt / 90f);
            dragLift += (1f - dragLift) * (1f - (float) Math.exp(-dt / 90f));
            dragHoldMs = 0f;
            dragVortex *= (float) Math.exp(-dt / 160f);
        }

        final float spinP = spinProgress(now);
        final float spin = spinOffset(spinP);
        final float gather = spinGather(spinP);
        final boolean spinning = spinP > 0.0001f;
        if (spinning && lastSpinProgress <= 0.0001f) {
            // Fresh fling: drop the physical displacement, the ring has to be
            // perfectly round for the "orbit around the avatar" illusion.
            for (int i = 0; i < gifts.size(); i++) {
                final Gift g = gifts.get(i);
                g.physX = g.physY = g.velX = g.velY = 0f;
            }
        }
        lastSpinProgress = spinP;

        final float t = (now - animationsStart) / 1000f;
        final float musicScale = 1f + 0.055f * musicKick + 0.02f * musicLevel;

        // Pass 1: layout targets. Nothing is drawn yet, so the physics pass
        // below already sees this frame's positions of every single bubble.
        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat != null ? gift.animatedFloat.set(1.0f) : 1f;
            final float phase = gift.idlePhase;
            final float hover = (float) Math.sin((t / gift.hoverDuration + phase) * Math.PI * 2.0) * dpf2(1.6f);
            final float wiggle = (float) Math.sin((t / gift.wiggleDuration + phase * 1.7f) * Math.PI * 2.0) * 0.04f;
            final float glow = 0.8f + 0.2f * (float) Math.sin((t / gift.glowDuration + phase * 0.6f) * Math.PI * 2.0);

            float distance = gift.orbitDistance;
            float scale = gift.orbitScale;
            float front = 0f;
            if (gather > 0.001f) {
                // On a fast release every bubble is pulled into a tight ring
                // hugging the avatar, so from the very first moment the spheres
                // cross its silhouette and the orbit reads as "goes behind the
                // avatar and comes back in front".
                final float ring = avatarRadius * (0.90f + 0.22f * (i % 4) / 3f);
                distance = lerp(distance, ring, 0.99f * gather);
                scale *= 1f + 0.07f * gather;
            }
            final float angle = gift.orbitAngle + spin;
            if (gather > 0.001f) {
                // Positive sine = near half of the orbit = in front of the
                // avatar, negative = the far half that passes behind it.
                front = (float) Math.sin(angle) * gather;
            }

            float gx = acx + (float) Math.cos(angle) * distance;
            // Flatten the orbit while spinning, so the near half passes right
            // across the lower part of the photo and the far half hides behind
            // the upper one; at rest the layout is used untouched.
            float gy = acy + (float) Math.sin(angle) * distance * (1f - 0.68f * gather) + hover;
            gy += front * avatarRadius * 0.10f;
            scale *= 1f + 0.16f * front;
            gift.baseCx = gx;
            gift.baseCy = gy;

            // The user offset fades out while the bubbles spin around the
            // avatar, so the whole collection forms one clean ring.
            final float offsetFade = 1f - gather;
            final boolean hasOffset = (gift.offsetX != 0f || gift.offsetY != 0f)
                    && !Float.isNaN(gift.offsetX) && !Float.isInfinite(gift.offsetX)
                    && !Float.isNaN(gift.offsetY) && !Float.isInfinite(gift.offsetY);
            if (hasOffset) {
                gx += gift.offsetX * avatarSize * offsetFade;
                gy += gift.offsetY * avatarSize * offsetFade;
                // Keep custom positions on-screen after rotation / resize.
                gx = clamp(gx, dragPad(), Math.max(dragPad(), getWidth() - dragPad()));
                gy = clamp(gy, dragTop(), Math.max(dragTop(), dragBottom() - dp(27)));
            }

            // Fly out of the avatar on first appearance, like the iOS cover.
            gx = lerp(acx, gx, enter);
            gy = lerp(acy, gy, enter);
            scale *= lerp(0.2f, 1f, enter);

            if (gift != draggingGift && gift.settleStart != 0L) {
                // Smooth glide from where the finger let go back into place,
                // with a small overshoot of the lift scale.
                final float sp = (now - gift.settleStart) / 280f;
                if (sp >= 1f) {
                    gift.settleStart = 0L;
                } else {
                    final float e = easeOutCubic(sp);
                    gx = lerp(gift.settleFromX, gx, e);
                    gy = lerp(gift.settleFromY, gy, e);
                    scale *= 1f + (gift.settleFromScale - 1f) * (1f - e)
                            + 0.06f * (float) Math.sin(sp * Math.PI) * (1f - sp);
                }
            }

            if (!spinning && gift.settleStart == 0L && enter >= 0.999f) {
                // Resting bubbles must stay reachable: never hidden under the
                // action bar and never pushed off the screen after a spin. The
                // bounds match dragTop()/dragBottom(), so the position a bubble
                // is dropped at is exactly the position it rests at.
                gx = clamp(gx, dp(10), Math.max(dp(10), getWidth() - dp(10)));
                gy = clamp(gy, dragTop(), Math.max(dragTop(), dragBottom() - dp(27)));
            }

            gift.drawX = gx;
            gift.drawY = gy;
            gift.drawScale = scale * musicScale;
            gift.drawAlpha = visibility * enter * (1f - 0.14f * Math.max(0f, -front));
            gift.drawGlow = glow + 0.6f * musicKick;
            gift.drawFront = front;
            gift.drawWiggle = wiggle + 0.05f * musicKick * (float) Math.sin(angle * 2.0);
        }

        // Pass 2: physics. The orbit ring has to stay perfectly round, so the
        // simulation is paused for the duration of the fling.
        if (!spinning && DeltamentProfileMusic.isPhysicsEnabled()) {
            updatePhysics(dt / 1000f, acx, acy, avatarRadius, spinning);
        } else {
            final float decay = (float) Math.exp(-dt / 90f);
            for (int i = 0; i < gifts.size(); i++) {
                final Gift g = gifts.get(i);
                g.physX *= decay;
                g.physY *= decay;
                g.velX *= decay;
                g.velY *= decay;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            if (g == draggingGift || g == heldGift) continue;
            g.drawX += g.physX;
            g.drawY += g.physY;
            g.lastCx = g.drawX;
            g.lastCy = g.drawY;
        }

        if (catOn) {
            updateCat(dt, now, acx, acy, avatarRadius, viewBottom, visibility);
        }

        canvas.save();
        canvas.clipRect(0, 0, getWidth(), viewBottom);

        if (musicVisible) {
            drawMusicVisualizer(canvas, acx, acy, avatarRadius, visibility);
        }

        drawConstellationDim(canvas);
        drawConstellations(canvas, now, visibility);

        // Pass 3: draw back-to-front so overlapping spheres stack correctly.
        // Bubbles on the far side of the orbit are clipped out of the avatar.
        final int count = drawOrderForFrame();
        for (int i = 0; i < count; i++) {
            drawGiftWithDepth(canvas, drawOrder[i], acx, acy, avatarRadius);
        }

        if (catOn) {
            drawCatHearts(canvas, now, visibility);
        }
        drawHeldGift(canvas, now, visibility);
        if (catOn) {
            drawCat(canvas, now, visibility);
        }
        drawGiftOfDayCard(canvas, acx, acy, avatarRadius, visibility);

        if (draggingGift != null) {
            draggingGift.lastCx = dragRenderX;
            draggingGift.lastCy = dragRenderY;
            draggingGift.draw(canvas, dragRenderX, dragRenderY, dragLift * musicScale, 0f, 1f, 1.2f,
                    dragStretch, dragStretchAngle);
        }

        drawConstellationButton(canvas, now, visibility);

        canvas.restore();

        if (isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }

    /** Builds the depth-sorted draw list of every bubble except the dragged one. */
    private int drawOrderForFrame() {
        int count = 0;
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            if (gift == draggingGift || gift == heldGift) continue;
            if (count == drawOrder.length) {
                final Gift[] bigger = new Gift[Math.max(4, drawOrder.length * 2)];
                System.arraycopy(drawOrder, 0, bigger, 0, drawOrder.length);
                drawOrder = bigger;
            }
            // Insertion sort, back-most first: at most six bubbles.
            int j = count - 1;
            while (j >= 0 && drawOrder[j].drawFront > gift.drawFront) {
                drawOrder[j + 1] = drawOrder[j];
                j--;
            }
            drawOrder[j + 1] = gift;
            count++;
        }
        return count;
    }

    private void drawGiftWithDepth(Canvas canvas, Gift gift, float acx, float acy, float avatarRadius) {
        float scale = gift.drawScale;
        float glow = gift.drawGlow;
        float wiggle = gift.drawWiggle;
        if (gift == catGift && catAttention > 0.001f) {
            // The bubble the companion is staring at gets a soft pulse.
            scale *= 1f + 0.10f * catAttention;
            glow += 1.1f * catAttention;
            wiggle += 0.025f * catAttention
                    * (float) Math.sin(SystemClock.uptimeMillis() / 170.0);
        }
        final boolean behind = gift.drawFront < -0.02f;
        if (behind) {
            final float dx = gift.drawX - acx;
            final float dy = gift.drawY - acy;
            final float reach = avatarRadius + dp(27) * gift.drawScale;
            if (dx * dx + dy * dy < reach * reach) {
                // Draw into a layer and clear the avatar silhouette, so the
                // sphere genuinely disappears behind the photo and reappears
                // on the other side of the orbit.
                depthLayerRect.set(
                        gift.drawX - dp(80), gift.drawY - dp(80),
                        gift.drawX + dp(80), gift.drawY + dp(80)
                );
                final int save = canvas.saveLayer(depthLayerRect, null);
                gift.draw(canvas, gift.drawX, gift.drawY, scale, wiggle,
                        gift.drawAlpha, glow, 0f, 0f);
                canvas.drawCircle(acx, acy, avatarRadius, avatarMaskPaint);
                canvas.restoreToCount(save);
                return;
            }
        }
        gift.draw(canvas, gift.drawX, gift.drawY, scale, wiggle,
                gift.drawAlpha, glow, 0f, 0f);
    }

    private float giftCenterX(Gift gift) {
        return gift == draggingGift ? dragRenderX : gift.drawX;
    }

    private float giftCenterY(Gift gift) {
        return gift == draggingGift ? dragRenderY : gift.drawY;
    }

    /**
     * Lightweight Verlet/spring simulation for the bubbles: collisions between
     * spheres, repulsion when the finger pushes them, attraction towards the
     * dragged bubble and a growing vortex while it is held still. The result is
     * only a displacement from the layout slot, so the saved positions and the
     * fling ring stay untouched.
     */
    private void updatePhysics(float dtSec, float acx, float acy, float avatarRadius, boolean spinning) {
        final float step = Math.min(dtSec, 0.05f);
        if (step <= 0f) return;

        final float bubbleRadius = dp(27);
        final float minDist = bubbleRadius * 1.92f;
        final float springK = draggingGift != null ? 9f : 18f;
        final float damping = (float) Math.exp(-step * 4.5f);
        final float maxSpeed = dp(2600);
        final float maxOffset = dp(92);
        final int n = gifts.size();

        for (int i = 0; i < n; i++) {
            final Gift gift = gifts.get(i);
            if (gift == draggingGift || gift == heldGift) {
                gift.physX = gift.physY = gift.velX = gift.velY = 0f;
                continue;
            }
            if (SystemClock.uptimeMillis() < gift.holdUntil) {
                // Just dropped: hold the exact release point.
                gift.physX = gift.physY = gift.velX = gift.velY = 0f;
                continue;
            }
            float ax = -springK * gift.physX;
            float ay = -springK * gift.physY;

            // Never drift onto the avatar unless it is orbiting it.
            if (!spinning) {
                final float dx = gift.drawX - acx;
                final float dy = gift.drawY - acy;
                final float d = (float) Math.hypot(dx, dy);
                final float keep = avatarRadius + bubbleRadius * 0.92f;
                if (d > 0.01f && d < keep) {
                    final float push = (keep - d) * 1400f;
                    ax += dx / d * push;
                    ay += dy / d * push;
                }
            }

            // Finger: attraction, hard push when too close, tangential swirl.
            if (draggingGift != null) {
                final float dx = dragRenderX - gift.drawX;
                final float dy = dragRenderY - gift.drawY;
                final float d = (float) Math.hypot(dx, dy);
                final float range = dp(190);
                if (d > 0.01f && d < range) {
                    final float nx = dx / d;
                    final float ny = dy / d;
                    final float falloff = 1f - d / range;
                    final float radial = d < minDist
                            ? -((minDist - d) / minDist) * 3600f
                            : 2200f;
                    ax += nx * radial * falloff;
                    ay += ny * radial * falloff;
                    final float swirl = dragVortex * 2600f * falloff;
                    ax -= ny * swirl;
                    ay += nx * swirl;
                }
            }

            // Sphere <-> sphere collisions.
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                final Gift other = gifts.get(j);
                final float dx = gift.drawX - giftCenterX(other);
                final float dy = gift.drawY - giftCenterY(other);
                final float d = (float) Math.hypot(dx, dy);
                if (d > 0.01f && d < minDist) {
                    final float overlap = (minDist - d) / minDist;
                    ax += dx / d * overlap * 3000f;
                    ay += dy / d * overlap * 3000f;
                }
            }

            gift.velX = (gift.velX + ax * step) * damping;
            gift.velY = (gift.velY + ay * step) * damping;
            final float speed = (float) Math.hypot(gift.velX, gift.velY);
            if (speed > maxSpeed) {
                gift.velX = gift.velX / speed * maxSpeed;
                gift.velY = gift.velY / speed * maxSpeed;
            }
            gift.physX = clamp(gift.physX + gift.velX * step, -maxOffset, maxOffset);
            gift.physY = clamp(gift.physY + gift.velY * step, -maxOffset, maxOffset);
            if (Math.abs(gift.physX) >= maxOffset) gift.velX *= -0.35f;
            if (Math.abs(gift.physY) >= maxOffset) gift.velY *= -0.35f;
        }
    }

    /** Smooths the analytic beat clock so the visuals never jitter. */
    private void updateMusicPulse(float dt) {
        musicTime += dt / 1000f;
        final float beat = DeltamentProfileMusic.getBeat();
        final float level = DeltamentProfileMusic.getLevel();
        if (beat >= musicKick) {
            musicKick = beat;
        } else {
            musicKick = Math.max(0f, musicKick - dt / 240f);
        }
        musicLevel += (level - musicLevel) * (1f - (float) Math.exp(-dt / 150f));
    }

    /** Radial equalizer and bloom around the avatar while the track plays. */
    private void drawMusicVisualizer(Canvas canvas, float acx, float acy, float avatarRadius, float visibility) {
        if (avatarRadius <= 0f) return;

        final int skin = DeltamentBubbleSkins.get();
        final int accent = DeltamentBubbleSkins.isDefault(skin) ? 0xFFFFFFFF : DeltamentBubbleSkins.accent(skin);
        ensureMusicGlowShader(avatarRadius, accent);

        final float kick = musicKick;
        final float level = musicLevel;

        canvas.save();
        canvas.translate(acx, acy);
        musicGlowPaint.setShader(musicGlowShader);
        musicGlowPaint.setAlpha((int) ((46f + 104f * kick) * visibility * Math.min(1f, level * 1.6f)));
        canvas.drawCircle(0, 0, avatarRadius * 1.8f, musicGlowPaint);
        canvas.restore();

        musicBarPaint.setStyle(Paint.Style.STROKE);
        musicBarPaint.setStrokeCap(Paint.Cap.ROUND);

        // Thin ring hugging the avatar, pulsing with the downbeat.
        musicBarPaint.setStrokeWidth(dp(1.4f));
        musicBarPaint.setColor(accent);
        musicBarPaint.setAlpha((int) ((34f + 120f * kick) * visibility));
        canvas.drawCircle(acx, acy, avatarRadius * (1.015f + 0.035f * kick), musicBarPaint);

        // Rotating bars.
        final int bars = 54;
        final float inner = avatarRadius * 1.12f;
        final float maxLen = dp(15);
        musicBarPaint.setStrokeWidth(dp(2.1f));
        musicBarPaint.setAlpha((int) (150f * visibility * Math.min(1f, 0.25f + level)));
        canvas.save();
        canvas.rotate(musicTime * 16f, acx, acy);
        for (int i = 0; i < bars; i++) {
            final float a = (float) (i * Math.PI * 2.0 / bars);
            final float shapeA = 0.5f + 0.5f * (float) Math.sin(i * 1.93f + musicTime * 3.6f);
            final float shapeB = 0.5f + 0.5f * (float) Math.cos(i * 0.71f - musicTime * 2.2f);
            final float len = dp(1.5f)
                    + maxLen * (0.16f + 0.62f * level) * (0.3f + 0.7f * shapeA * shapeB)
                    + maxLen * 0.5f * kick * shapeA;
            final float cos = (float) Math.cos(a);
            final float sin = (float) Math.sin(a);
            canvas.drawLine(
                    acx + cos * inner, acy + sin * inner,
                    acx + cos * (inner + len), acy + sin * (inner + len),
                    musicBarPaint
            );
        }
        canvas.restore();
    }

    private void ensureMusicGlowShader(float radius, int accent) {
        if (musicGlowShader != null && Math.abs(musicGlowRadius - radius) < 0.5f
                && musicGlowAccent == accent) {
            return;
        }
        musicGlowRadius = radius;
        musicGlowAccent = accent;
        final float outer = radius * 1.8f;
        musicGlowShader = new RadialGradient(
                0, 0, outer,
                new int[]{
                        0x00000000,
                        0x00000000,
                        Theme.multAlpha(accent, 0.42f),
                        Theme.multAlpha(accent, 0.0f)
                },
                new float[]{0f, 0.52f, 0.74f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    private static float easeOutCubic(float p) {
        final float q = 1f - p;
        return 1f - q * q * q;
    }

    /** Progress of the post-release spin, 0 when no spin is running. */
    private float spinProgress(long now) {
        if (flingSpinStart < 0L) {
            return 0f;
        }
        final long elapsed = now - flingSpinStart;
        if (elapsed >= flingSpinDuration) {
            flingSpinStart = -1L;
            return 0f;
        }
        return elapsed / (float) flingSpinDuration;
    }

    /** Eased spin offset; whole turns, so it lands exactly on the base layout. */
    private float spinOffset(float progress) {
        if (progress <= 0f) {
            return 0f;
        }
        return flingSpinDir * flingSpinTurns * (float) (Math.PI * 2) * spinEase(progress);
    }

    /**
     * Slope of the spin over time, built from a speed profile: a short ramp-in,
     * a long cruise, then a long ramp-out (the last 22 % of the duration). The
     * integral is normalised so ease(1) == 1 and the derivative at the end is
     * zero — the orbit decelerates into the layout instead of stopping dead.
     */
    private static float spinEase(float progress) {
        final float p = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        final float in = 0.06f;
        final float out = 0.22f;
        final float total = 1f - in / 2f - out / 2f;
        final float sum;
        if (p < in) {
            sum = p * p / (2f * in);
        } else if (p <= 1f - out) {
            sum = in / 2f + (p - in);
        } else {
            final float t = 1f - p;
            sum = in / 2f + (1f - out - in) + out / 2f - t * t / (2f * out);
        }
        return sum / total;
    }

    /**
     * How strongly the bubbles are pulled into the orbit ring. It snaps in
     * almost immediately (so the very first moment already passes behind the
     * avatar), holds for most of the spin, then dissolves over the last 20 %
     * with a smootherstep: the return to the own slots takes ~1.1 s and has no
     * visible snap at either end.
     */
    private static float spinGather(float progress) {
        if (progress <= 0f || progress >= 1f) {
            return 0f;
        }
        final float rise = 0.04f;
        final float fall = 0.20f;
        if (progress < rise) {
            final float g = progress / rise;
            return g * g * (3f - 2f * g);
        }
        if (progress > 1f - fall) {
            final float g = (1f - progress) / fall;
            return g * g * g * (g * (g * 6f - 15f) + 10f);
        }
        return 1f;
    }

    /**
     * Port of the orbit layout from Telegram for iOS
     * (PeerInfoGiftsCoverComponent.PositionGenerator): the first four bubbles
     * sit on a tight orbit around the avatar, the rest on an outer one, while
     * the status bar, navigation buttons and the title stay clear.
     */
    private void computePositions(float acx, float acy, float avatarSize, float headerBottom) {
        final int count = gifts.size();
        if (count <= 0) {
            positionsDirty = false;
            return;
        }

        final float w = Math.max(getWidth(), 1);
        final float radius = avatarSize / 2f;
        // On someone else's profile the collection must stay in the header band
        // around the avatar: it used to be scattered over the whole page, on top
        // of the bio, the gift cards and the "send gift" button. Only the owner
        // gets the full-page spread, because only the owner can rearrange it.
        final float ownerBottom = headerBottom;
        final float foreignBottom = acy + radius + dp(180);
        final float h = Math.max(canDrag ? ownerBottom : Math.min(ownerBottom, foreignBottom), dp(240));
        final float item = dp(52);
        final float pad = dp(6);
        final float minDistance = dp(58);

        final float barBottom = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
        final float navTop = h - Math.max(AndroidUtilities.navigationBarHeight, dp(8)) - dp(4);

        final ArrayList<RectF> exclusions = new ArrayList<>();
        // The whole top strip (status bar + tab bar), not just its corners:
        // bubbles that end up there cannot be grabbed on most devices.
        exclusions.add(new RectF(0, 0, w, barBottom + dp(2)));
        // The avatar itself.
        exclusions.add(new RectF(acx - radius - dp(10), acy - radius - dp(10),
                acx + radius + dp(10), acy + radius + dp(10)));
        // Name, status and the action capsules under the avatar. On foreign
        // profiles the block is a little shorter, so the bubbles that do not
        // fit around the avatar land in one tidy row right under the capsules
        // instead of falling back to the screen edges.
        final float blockTop = acy + radius + dp(4);
        final float blockWidth = Math.min(w - dp(16), dp(340));
        final float blockBottom = Math.min(navTop - dp(4), blockTop + dp(canDrag ? 126 : 112));
        exclusions.add(new RectF((w - blockWidth) / 2f, blockTop, (w + blockWidth) / 2f, blockBottom));
        // System navigation bar.
        exclusions.add(new RectF(0, navTop, w, h));

        final float[] distances = new float[count];
        final float[] angles = new float[count];
        final float[] scales = new float[count];
        int placed = 0;
        int left = 0, right = 0;
        int attempts = 0;

        // Only the closest bubbles hug the avatar, the rest is spread over the
        // whole profile (see the scatter loop below).
        final int innerCount = Math.min(3, count);
        final float maxPossibleDistance = (float) Math.hypot(w, h);

        while (placed < innerCount && attempts < count * 250) {
            attempts++;
            final boolean onLeft = right > left;
            final float distance = (1.4f + 0.8f * bubbleRandom.nextFloat()) * radius;
            final float angle = (onLeft ? (float) Math.PI / 2f : -(float) Math.PI / 2f)
                    + (float) Math.PI * bubbleRandom.nextFloat();
            final float px = acx + (float) Math.cos(angle) * distance;
            final float py = acy + (float) Math.sin(angle) * distance;
            if (!isPositionFree(px, py, item, pad, w, navTop, exclusions, distances, angles, placed, acx, acy, minDistance)) {
                continue;
            }
            distances[placed] = distance;
            angles[placed] = angle;
            scales[placed] = 0.88f + 0.17f * bubbleRandom.nextFloat();
            placed++;
            if (px < acx) left++; else right++;
        }

        // Everything else is spread over the free space of the whole profile,
        // mostly below the avatar and along both side margins.
        while (placed < count && attempts < count * 900) {
            attempts++;
            final float usableTop = dragTop();
            final float usableBottom = navTop - dp(4);
            final float px, py;
            if (bubbleRandom.nextFloat() < 0.68f) {
                // Prefer the open area under the title/actions block.
                final float regionTop = Math.min(usableBottom - item, blockBottom + dp(8));
                px = pad + item / 2f + bubbleRandom.nextFloat() * Math.max(1f, w - item - 2f * pad);
                py = regionTop + item / 2f + bubbleRandom.nextFloat()
                        * Math.max(1f, usableBottom - regionTop - item);
            } else {
                px = pad + item / 2f + bubbleRandom.nextFloat() * Math.max(1f, w - item - 2f * pad);
                py = usableTop + item / 2f + bubbleRandom.nextFloat()
                        * Math.max(1f, usableBottom - usableTop - item);
            }
            if (!isPositionFree(px, py, item, pad, w, navTop, exclusions, distances, angles, placed, acx, acy, minDistance)) {
                continue;
            }
            final float dx = px - acx, dy = py - acy;
            final float dist = (float) Math.hypot(dx, dy);
            distances[placed] = dist;
            angles[placed] = (float) Math.atan2(dy, dx);
            scales[placed] = Math.max(0.72f, 1.02f - Math.min(1f, dist / Math.max(1f, maxPossibleDistance)) * 0.26f);
            placed++;
            if (px < acx) left++; else right++;
        }

        // Last-resort fallback: a column at the edges, never over the bars.
        while (placed < count) {
            final boolean rightSide = placed % 2 == 0;
            final float px = rightSide ? w - pad - item / 2f : pad + item / 2f;
            final float py = Math.min(navTop - pad - item / 2f,
                    blockBottom + dp(20) + (placed / 2) * (item + dp(8)));
            final float dx = px - acx, dy = py - acy;
            distances[placed] = (float) Math.hypot(dx, dy);
            angles[placed] = (float) Math.atan2(dy, dx);
            scales[placed] = 0.82f;
            placed++;
        }

        for (int i = 0; i < count; i++) {
            final Gift gift = gifts.get(i);
            gift.orbitDistance = distances[i];
            gift.orbitAngle = angles[i];
            gift.orbitScale = scales[i];
        }

        positionsWidth = getWidth();
        positionsHeight = getHeight();
        positionsExpandY = headerBottom;
        positionsCx = acx;
        positionsCy = acy;
        positionsDirty = false;
    }

    private boolean isPositionFree(
            float px, float py, float item, float pad, float w, float h,
            ArrayList<RectF> exclusions,
            float[] distances, float[] angles, int placed,
            float acx, float acy, float minDistance
    ) {
        if (px - item / 2f < pad || px + item / 2f > w - pad
                || py - item / 2f < pad || py + item / 2f > h - pad) {
            return false;
        }
        final float half = item / 2f;
        for (int i = 0; i < exclusions.size(); i++) {
            final RectF zone = exclusions.get(i);
            if (px + half > zone.left && px - half < zone.right
                    && py + half > zone.top && py - half < zone.bottom) {
                return false;
            }
        }
        final float effectiveMinDistance = placed > 5 ? Math.max(minDistance * 0.7f, dp(10)) : minDistance;
        for (int i = 0; i < placed; i++) {
            final float ex = acx + (float) Math.cos(angles[i]) * distances[i];
            final float ey = acy + (float) Math.sin(angles[i]) * distances[i];
            if (Math.hypot(ex - px, ey - py) < effectiveMinDistance) {
                return false;
            }
        }
        return true;
    }

    public Gift getGiftUnder(float x, float y) {
        for (int i = gifts.size() - 1; i >= 0; --i) {
            final Gift gift = gifts.get(i);
            if (gift == heldGift) continue;
            if (gift.bounds.contains(x, y))
                return gift;
        }
        return null;
    }

    private Gift pressedGift;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            final float x = event.getX();
            final float y = event.getY();

            // Collectible card: any tap on it sends the gift back home.
            if (cardAlpha > 0.35f && cardRect.contains(x, y)) {
                dismissGiftOfDay();
                return true;
            }

            // Constellation editor toggle.
            if (consButtonVisible) {
                final float bdx = x - consButtonX;
                final float bdy = y - consButtonY;
                if (bdx * bdx + bdy * bdy <= consButtonR * consButtonR * 2.2f) {
                    consEdit = !consEdit;
                    consFrom = null;
                    consTapEmpty = false;
                    if (consEdit) {
                        consHintStart = 0L;
                    }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
            }

            if (consEdit) {
                final Gift hit = getGiftUnder(x, y);
                if (hit != null) {
                    consFrom = hit;
                    consTouchX = x;
                    consTouchY = y;
                    return true;
                }
                final int line = linkUnder(x, y);
                if (line >= 0) {
                    constellations.remove(line);
                    saveConstellations();
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                consTapEmpty = true;
                downX = x;
                downY = y;
                return true;
            }

            // Tap on the companion offers the "gift of the day" again.
            if (canDrag && catAlpha > 0.3f && heldGift == null
                    && (catState == CAT_WANDER || catState == CAT_LOOK)) {
                final float cdx = x - catX;
                final float cdy = y - catY;
                if (cdx * cdx + cdy * cdy <= dp(36) * dp(36)) {
                    catTapPending = true;
                    downX = x;
                    downY = y;
                    return true;
                }
            }

            final Gift hit = getGiftUnder(x, y);
            pressedGift = hit;
            downX = x;
            downY = y;
            lastMoveX = downX;
            lastMoveY = downY;
            lastMoveTime = event.getEventTime();
            dragVelX = dragVelY = 0f;
            if (pressedGift == null) {
                return false;
            }
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            } else {
                velocityTracker.clear();
            }
            velocityTracker.addMovement(event);
            pressedGift.bounce.setPressed(true);
            if (canDrag) {
                dragLongPressPosted = true;
                removeCallbacks(dragLongPressRunnable);
                postDelayed(dragLongPressRunnable, DRAG_LONG_PRESS_MS);
            }
            return true;
        }

        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }

        if (consEdit) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (consFrom != null) {
                    consTouchX = event.getX();
                    consTouchY = event.getY();
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (consFrom != null) {
                    final Gift target = getGiftUnder(event.getX(), event.getY());
                    if (target != null && target != consFrom) {
                        toggleLink(consFrom.id, target.id);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    consFrom = null;
                    invalidate();
                } else if (consTapEmpty) {
                    consTapEmpty = false;
                    consEdit = false;
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                consFrom = null;
                consTapEmpty = false;
                return true;
            }
        }

        if (catTapPending) {
            if (action == MotionEvent.ACTION_UP) {
                catTapPending = false;
                triggerGiftOfDay();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                catTapPending = false;
            }
            return true;
        }

        if (draggingGift != null) {
            if (action == MotionEvent.ACTION_MOVE) {
                final long eventTime = event.getEventTime();
                final float moveDt = Math.max(1f, eventTime - lastMoveTime);
                dragVelX = lerp(dragVelX, (event.getX() - lastMoveX) / moveDt, 0.45f);
                dragVelY = lerp(dragVelY, (event.getY() - lastMoveY) / moveDt, 0.45f);
                lastMoveX = event.getX();
                lastMoveY = event.getY();
                lastMoveTime = eventTime;
                dragCx = clamp(event.getX() + dragTouchOffsetX, dragPad(), Math.max(dragPad(), getWidth() - dragPad()));
                dragCy = clamp(event.getY() + dragTouchOffsetY, dragTop(),
                        Math.max(dragTop(), dragBottom() - dp(27)));
                // Some parents intercept the first MOVE even after the initial
                // request, so keep asking while the bubble is being dragged.
                final ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP && velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    lastVelocityX = velocityTracker.getXVelocity();
                    lastVelocityY = velocityTracker.getYVelocity();
                } else {
                    // A cancelled gesture must not inherit a stale velocity and
                    // accidentally start a spin.
                    lastVelocityX = lastVelocityY = 0f;
                }
                releaseVelocityTracker();
                // Even a cancelled gesture (a system swipe, an incoming dialog)
                // keeps the last position, so the bubble never jumps back to a
                // stale slot after the finger disappears.
                finishDrag(true);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (pressedGift != null) {
                final float dx = event.getX() - downX;
                final float dy = event.getY() - downY;
                final float slop = AndroidUtilities.touchSlop;
                if (dx * dx + dy * dy > slop * slop) {
                    cancelDragLongPress();
                    pressedGift.bounce.setPressed(false);
                    pressedGift = null;
                }
            }
        } else if (action == MotionEvent.ACTION_UP) {
            cancelDragLongPress();
            if (pressedGift != null) {
                onGiftClick(pressedGift);
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
            releaseVelocityTracker();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelDragLongPress();
            if (pressedGift != null) {
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
            releaseVelocityTracker();
        }
        return pressedGift != null;
    }

    private void startDrag() {
        dragLongPressPosted = false;
        if (!canDrag || pressedGift == null || !active) {
            return;
        }
        final Gift gift = pressedGift;
        pressedGift = null;
        cancelGiftOfDay();
        draggingGift = gift;
        gift.bounce.setPressed(false);
        gift.settleStart = 0L;
        dragCx = gift.lastCx;
        dragCy = gift.lastCy;
        dragRenderX = dragCx;
        dragRenderY = dragCy;
        dragLift = 1f;
        dragStretch = 0f;
        dragVelX = dragVelY = 0f;
        dragHoldMs = 0f;
        dragVortex = 0f;
        gift.physX = gift.physY = gift.velX = gift.velY = 0f;
        lastMoveX = downX;
        lastMoveY = downY;
        lastMoveTime = SystemClock.uptimeMillis();
        dragStartTime = lastMoveTime;
        dragTouchOffsetX = dragCx - downX;
        dragTouchOffsetY = dragCy - downY;
        if (velocityTracker != null) {
            velocityTracker.clear();
        }
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void finishDrag(boolean apply) {
        final Gift gift = draggingGift;
        draggingGift = null;
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
        if (gift != null && apply) {
            final long now = SystemClock.uptimeMillis();
            final float speed = (float) Math.hypot(lastVelocityX, lastVelocityY);
            // Always persist where the user let go. Previously a "fast" release
            // skipped this, so an ordinary drag was treated as a fling, the whole
            // collection orbited for ~5 s and the bubble ended up exactly where
            // it started — which felt like "I move it and it goes back".
            if (lastAvatarWidth > 0f && lastAvatarHeight > 0f) {
                final float nx = (dragCx - gift.baseCx) / lastAvatarWidth;
                final float ny = (dragCy - gift.baseCy) / lastAvatarHeight;
                if (!Float.isNaN(nx) && !Float.isInfinite(nx) && !Float.isNaN(ny) && !Float.isInfinite(ny)) {
                    gift.offsetX = clamp(nx, -6f, 6f);
                    gift.offsetY = clamp(ny, -6f, 6f);
                    savePosition(gift);
                }
            }
            // A real flick — a short gesture that is still moving at the moment
            // of release — still starts the orbit. A deliberate drag must not:
            // it has to simply stay where it was dropped.
            final boolean flick = speed > dp(1500)
                    && (now - lastMoveTime) <= 110L
                    && (now - dragStartTime) <= 520L;
            if (flick) {
                gift.settleStart = 0L;
                startFlingSpin(speed, lastVelocityX, lastVelocityY);
            } else {
                // Glide from the render position (which lags the finger) into
                // the saved slot, then stay put: collisions must not shove the
                // bubble away from the point the user chose.
                gift.settleStart = now;
                gift.settleFromX = dragRenderX;
                gift.settleFromY = dragRenderY;
                gift.settleFromScale = dragLift;
                gift.holdUntil = now + 800L;
            }
        }
        lastVelocityX = lastVelocityY = 0f;
        invalidate();
    }

    /** Fast release: all bubbles gather around the avatar and orbit it. */
    private void startFlingSpin(float speed, float vx, float vy) {
        // An ongoing "gift of the day" ritual owns one bubble; release it first
        // so the orbit is built from every gift and nothing is left attached
        // to the companion mid-spin.
        cancelGiftOfDay();
        flingSpinStart = SystemClock.uptimeMillis();
        // Long enough to actually enjoy the orbit: two turns minimum, ~5.6 s.
        flingSpinDuration = 5600L;
        flingSpinTurns = speed > dp(2600) ? 4 : (speed > dp(1400) ? 3 : 2);
        lastSpinProgress = 0f;
        // A pending release glide would fight the orbit, and leftover physical
        // displacement would keep the ring from being perfectly round.
        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            g.settleStart = 0L;
            g.physX = g.physY = g.velX = g.velY = 0f;
        }
        final float direction = Math.abs(vx) > dp(120) ? vx : vy;
        flingSpinDir = direction >= 0f ? 1 : -1;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    private void releaseVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void cancelDragLongPress() {
        if (dragLongPressPosted) {
            dragLongPressPosted = false;
            removeCallbacks(dragLongPressRunnable);
        }
    }

    private float dragPad() {
        return dpf2(24);
    }

    /**
     * Topmost y a bubble may reach. The profile header is translucent, so the
     * bubbles are free to fly up to the status bar: the only limit keeps the
     * whole sphere inside the screen, it does not reserve the action bar any
     * more (that used to stop the drag ~34 dp below the top icons).
     */
    private float dragTop() {
        return AndroidUtilities.statusBarHeight + dp(27);
    }

    /** Lowest y a dragged bubble may reach (above the system navigation bar). */
    private float dragBottom() {
        final float nav = Math.max(AndroidUtilities.navigationBarHeight, dp(8));
        return Math.max(dp(200), getHeight() - nav);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
