package com.appboy.ui.inappmessage;

import android.app.Activity;
import android.support.v4.view.OnApplyWindowInsetsListener;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.WindowInsetsCompat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.appboy.configuration.AppboyConfigurationProvider;
import com.appboy.enums.inappmessage.DismissType;
import com.appboy.enums.inappmessage.SlideFrom;
import com.appboy.models.IInAppMessage;
import com.appboy.models.IInAppMessageImmersive;
import com.appboy.models.InAppMessageSlideup;
import com.appboy.models.MessageButton;
import com.appboy.support.AppboyLogger;
import com.appboy.ui.inappmessage.listeners.IInAppMessageViewLifecycleListener;
import com.appboy.ui.inappmessage.listeners.SwipeDismissTouchListener;
import com.appboy.ui.inappmessage.listeners.TouchAwareSwipeDismissTouchListener;
import com.appboy.ui.inappmessage.views.AppboyInAppMessageHtmlBaseView;
import com.appboy.ui.support.ViewUtils;

import java.util.List;

public class InAppMessageViewWrapper implements IInAppMessageViewWrapper {
  private static final String TAG = AppboyLogger.getAppboyLogTag(InAppMessageViewWrapper.class);

  private final View mInAppMessageView;
  private final IInAppMessage mInAppMessage;
  private final IInAppMessageViewLifecycleListener mInAppMessageViewLifecycleListener;
  private final Animation mOpeningAnimation;
  private final Animation mClosingAnimation;
  private final AppboyConfigurationProvider mAppboyConfigurationProvider;
  private boolean mIsAnimatingClose;
  private Runnable mDismissRunnable;
  private View mClickableInAppMessageView;
  private View mCloseButton;
  private List<View> mButtons;
  /**
   * The {@link FrameLayout} parent of the in-app message.
   */
  private FrameLayout mContentFrameLayout;

  /**
   * Constructor for base and slideup view wrappers. Adds click listeners to the in-app message view and
   * adds swipe functionality to slideup in-app messages.
   *
   * @param inAppMessageView In-app message top level view.
   * @param inAppMessage In-app message model.
   * @param inAppMessageViewLifecycleListener In-app message lifecycle listener.
   * @param appboyConfigurationProvider Configuration provider.
   * @param clickableInAppMessageView View for which click actions apply. Clicking any part of the top level view
   */
  public InAppMessageViewWrapper(View inAppMessageView, IInAppMessage inAppMessage, IInAppMessageViewLifecycleListener inAppMessageViewLifecycleListener,
                                 AppboyConfigurationProvider appboyConfigurationProvider, Animation openingAnimation, Animation closingAnimation, View clickableInAppMessageView) {
    mInAppMessageView = inAppMessageView;
    mInAppMessage = inAppMessage;
    mInAppMessageViewLifecycleListener = inAppMessageViewLifecycleListener;
    mAppboyConfigurationProvider = appboyConfigurationProvider;
    mIsAnimatingClose = false;
    if (clickableInAppMessageView != null) {
      mClickableInAppMessageView = clickableInAppMessageView;
    } else {
      mClickableInAppMessageView = mInAppMessageView;
    }

    // Only slideup in-app messages can be swiped.
    if (mInAppMessage instanceof InAppMessageSlideup) {
      // Adds the swipe listener to the in-app message View. All slideup in-app messages should be dismissible via a swipe
      // (even auto close slideup in-app messages).
      SwipeDismissTouchListener.DismissCallbacks dismissCallbacks = createDismissCallbacks();
      TouchAwareSwipeDismissTouchListener touchAwareSwipeListener = new TouchAwareSwipeDismissTouchListener(inAppMessageView, null, dismissCallbacks);
      // We set a custom touch listener that cancel the auto close runnable when touched and adds
      // a new runnable when the touch ends.
      touchAwareSwipeListener.setTouchListener(createTouchAwareListener());
      mClickableInAppMessageView.setOnTouchListener(touchAwareSwipeListener);
    }

    mOpeningAnimation = openingAnimation;
    mClosingAnimation = closingAnimation;

    // Set click listener on clickable in-app message view
    mClickableInAppMessageView.setOnClickListener(createClickListener());
  }

  /**
   * Constructor for immersive in-app message view wrappers. Adds listeners to an optional close button and
   * message button views.
   *
   * @param inAppMessageView In-app message top level view.
   * @param inAppMessage In-app message model.
   * @param inAppMessageViewLifecycleListener In-app message lifecycle listener.
   * @param appboyConfigurationProvider Configuration provider.
   * @param buttons List of views corresponding to MessageButton objects stored in the in-app message model object.
   *                These views should map one to one with the MessageButton objects.
   * @param closeButton
   */
  public InAppMessageViewWrapper(View inAppMessageView, IInAppMessage inAppMessage, IInAppMessageViewLifecycleListener inAppMessageViewLifecycleListener,
                                 AppboyConfigurationProvider appboyConfigurationProvider, Animation openingAnimation, Animation closingAnimation,
                                 View clickableInAppMessageView, List<View> buttons, View closeButton) {
    this(inAppMessageView, inAppMessage, inAppMessageViewLifecycleListener, appboyConfigurationProvider, openingAnimation, closingAnimation, clickableInAppMessageView);

    // Set close button click listener
    if (closeButton != null) {
      mCloseButton = closeButton;
      mCloseButton.setOnClickListener(createCloseInAppMessageClickListener());
    }

    // Set button click listeners
    if (buttons != null) {
      mButtons = buttons;
      for (View button : mButtons) {
        button.setOnClickListener(createButtonClickListener());
      }
    }
  }

  @Override
  public void open(Activity activity) {
    AppboyLogger.v(TAG, "Opening in-app message view wrapper");
    // Retrieve the FrameLayout view which will display the in-app message and its height. The
    // content FrameLayout contains the activity's top-level layout as its first child.
    final FrameLayout frameLayout = activity.getWindow().getDecorView().findViewById(android.R.id.content);
    int frameLayoutHeight = frameLayout.getHeight();
    final int displayHeight = ViewUtils.getDisplayHeight(activity);
    if (mAppboyConfigurationProvider.getIsInAppMessageAccessibilityExclusiveModeEnabled()) {
      mContentFrameLayout = frameLayout;
      setAllFrameLayoutChildrenAsNonAccessibilityImportant(mContentFrameLayout);
    }

    // If the FrameLayout height is 0, that implies it hasn't been drawn yet. We add a
    // ViewTreeObserver to wait until its drawn so we can get a proper measurement.
    if (frameLayoutHeight == 0) {
      ViewTreeObserver viewTreeObserver = frameLayout.getViewTreeObserver();
      if (viewTreeObserver.isAlive()) {
        viewTreeObserver.addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                AppboyLogger.d(TAG, "Detected root view height of " + frameLayout.getHeight() + ", display height of " + displayHeight + " in onGlobalLayout");
                frameLayout.removeView(mInAppMessageView);
                open(frameLayout);
                frameLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
              }
            });
      }
    } else {
      AppboyLogger.d(TAG, "Detected root view height of " + frameLayoutHeight + ", display height of " + displayHeight);
      open(frameLayout);
    }
  }

  private void open(FrameLayout frameLayout) {
    mInAppMessageViewLifecycleListener.beforeOpened(mInAppMessageView, mInAppMessage);
    AppboyLogger.d(TAG, "Adding In-app message view to root FrameLayout.");
    frameLayout.addView(mInAppMessageView, getLayoutParams());

    if (mInAppMessageView instanceof IInAppMessageView) {
      ViewCompat.requestApplyInsets(frameLayout);
      ViewCompat.setOnApplyWindowInsetsListener(frameLayout, new OnApplyWindowInsetsListener() {
        @Override
        public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
          if (insets == null) {
            // No margin fixing can be done with a null window inset
            return insets;
          }

          AppboyLogger.v(TAG, "Calling applyWindowInsets on in-app message view.");
          ((IInAppMessageView) mInAppMessageView).applyWindowInsets(insets);
          return insets;
        }
      });
    }

    if (mInAppMessage.getAnimateIn()) {
      AppboyLogger.d(TAG, "In-app message view will animate into the visible area.");
      setAndStartAnimation(true);
      // The afterOpened lifecycle method gets called when the opening animation ends.
    } else {
      AppboyLogger.d(TAG, "In-app message view will be placed instantly into the visible area.");
      // There is no opening animation, so we call the afterOpened lifecycle method immediately.
      if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
        addDismissRunnable();
      }
      ViewUtils.setFocusableInTouchModeAndRequestFocus(mInAppMessageView);
      announceForAccessibilityIfNecessary();
      mInAppMessageViewLifecycleListener.afterOpened(mInAppMessageView, mInAppMessage);
    }
  }

  private void announceForAccessibilityIfNecessary() {
    if (mInAppMessageView instanceof IInAppMessageImmersiveView) {
      mInAppMessageView.announceForAccessibility(mInAppMessage.getMessage());
    } else if (mInAppMessageView instanceof AppboyInAppMessageHtmlBaseView) {
      mInAppMessageView.announceForAccessibility("In-app message displayed.");
    }
  }

  private FrameLayout.LayoutParams getLayoutParams() {
    FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
    if (mInAppMessage instanceof InAppMessageSlideup) {
      InAppMessageSlideup inAppMessageSlideup = (InAppMessageSlideup) mInAppMessage;
      layoutParams.gravity = inAppMessageSlideup.getSlideFrom() == SlideFrom.TOP ? Gravity.TOP : Gravity.BOTTOM;
    }
    return layoutParams;
  }

  @Override
  public void close() {
    if (mAppboyConfigurationProvider.getIsInAppMessageAccessibilityExclusiveModeEnabled()) {
      setAllFrameLayoutChildrenAsAccessibilityAuto(mContentFrameLayout);
    }
    mInAppMessageView.removeCallbacks(mDismissRunnable);
    mInAppMessageViewLifecycleListener.beforeClosed(mInAppMessageView, mInAppMessage);
    if (mInAppMessage.getAnimateOut()) {
      mIsAnimatingClose = true;
      setAndStartAnimation(false);
    } else {
      closeInAppMessageView();
    }
  }

  @Override
  public View getInAppMessageView() {
    return mInAppMessageView;
  }

  @Override
  public IInAppMessage getInAppMessage() {
    return mInAppMessage;
  }

  @Override
  public boolean getIsAnimatingClose() {
    return mIsAnimatingClose;
  }

  private View.OnClickListener createClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // The onClicked lifecycle method is called and it can be used to turn off the close animation.
        // Full and modal in-app messages can only be clicked directly when they do not contain buttons.
        // Slideup in-app messages are always clickable.
        if (mInAppMessage instanceof IInAppMessageImmersive) {
          IInAppMessageImmersive inAppMessageImmersive = (IInAppMessageImmersive) mInAppMessage;
          if (inAppMessageImmersive.getMessageButtons() == null || inAppMessageImmersive.getMessageButtons().size() == 0) {
            mInAppMessageViewLifecycleListener.onClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), mInAppMessageView, mInAppMessage);
          }
        } else {
          mInAppMessageViewLifecycleListener.onClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), mInAppMessageView, mInAppMessage);
        }
      }
    };
  }

  private View.OnClickListener createButtonClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // The onClicked lifecycle method is called and it can be used to turn off the close animation.
        MessageButton messageButton;
        IInAppMessageImmersive inAppMessageImmersive = (IInAppMessageImmersive) mInAppMessage;
        for (int i = 0; i < mButtons.size(); i++) {
          if (view.getId() == mButtons.get(i).getId()) {
            messageButton = inAppMessageImmersive.getMessageButtons().get(i);
            mInAppMessageViewLifecycleListener.onButtonClicked(new InAppMessageCloser(InAppMessageViewWrapper.this), messageButton, inAppMessageImmersive);
            return;
          }
        }
      }
    };
  }

  private View.OnClickListener createCloseInAppMessageClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        AppboyInAppMessageManager.getInstance().hideCurrentlyDisplayingInAppMessage(true);
      }
    };
  }

  private void addDismissRunnable() {
    if (mDismissRunnable == null) {
      mDismissRunnable = new Runnable() {
        @Override
        public void run() {
          AppboyInAppMessageManager.getInstance().hideCurrentlyDisplayingInAppMessage(true);
        }
      };
      mInAppMessageView.postDelayed(mDismissRunnable, mInAppMessage.getDurationInMilliseconds());
    }
  }

  private SwipeDismissTouchListener.DismissCallbacks createDismissCallbacks() {
    return new SwipeDismissTouchListener.DismissCallbacks() {
      @Override
      public boolean canDismiss(Object token) {
        return true;
      }

      @Override
      public void onDismiss(View view, Object token) {
        mInAppMessage.setAnimateOut(false);
        AppboyInAppMessageManager.getInstance().hideCurrentlyDisplayingInAppMessage(true);
      }
    };
  }

  private TouchAwareSwipeDismissTouchListener.ITouchListener createTouchAwareListener() {
    return new TouchAwareSwipeDismissTouchListener.ITouchListener() {
      @Override
      public void onTouchStartedOrContinued() {
        mInAppMessageView.removeCallbacks(mDismissRunnable);
      }

      @Override
      public void onTouchEnded() {
        if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
          addDismissRunnable();
        }
      }
    };
  }

  /**
   * Instantiates and executes the correct animation for the current in-app message. Slideup-type
   * messages slide in from the top or bottom of the view. Other in-app messages fade in
   * and out of view.
   *
   * @param opening
   */
  private void setAndStartAnimation(boolean opening) {
    Animation animation;
    if (opening) {
      animation = mOpeningAnimation;
    } else {
      animation = mClosingAnimation;
    }
    animation.setAnimationListener(createAnimationListener(opening));
    mInAppMessageView.clearAnimation();
    mInAppMessageView.setAnimation(animation);
    animation.startNow();
    mInAppMessageView.invalidate();
  }

  private Animation.AnimationListener createAnimationListener(boolean opening) {
    if (opening) {
      return new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {}

        // This lifecycle callback has been observed to not be called during slideup animations
        // on occasion. Do not add any code that *MUST* be executed here.
        @Override
        public void onAnimationEnd(Animation animation) {
          if (mInAppMessage.getDismissType() == DismissType.AUTO_DISMISS) {
            addDismissRunnable();
          }
          AppboyLogger.d(TAG, "In-app message animated into view.");
          ViewUtils.setFocusableInTouchModeAndRequestFocus(mInAppMessageView);
          announceForAccessibilityIfNecessary();
          mInAppMessageViewLifecycleListener.afterOpened(mInAppMessageView, mInAppMessage);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {}
      };
    } else {
      return new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {}

        @Override
        public void onAnimationEnd(Animation animation) {
          mInAppMessageView.clearAnimation();
          mInAppMessageView.setVisibility(View.GONE);
          closeInAppMessageView();
        }

        @Override
        public void onAnimationRepeat(Animation animation) {}
      };
    }
  }

  /**
   * Closes the in-app message view.
   * In this order, the following actions are performed:
   * <ul>
   *  <li> The view is removed from the parent. </li>
   *  <li> Any WebViews have their {@link WebView#destroy()} methods called. </li>
   *  <li> {@link IInAppMessageViewLifecycleListener#afterClosed(IInAppMessage)} is called. </li>
   * </ul>
   */
  private void closeInAppMessageView() {
    AppboyLogger.d(TAG, "Closing in-app message view");
    ViewUtils.removeViewFromParent(mInAppMessageView);
    // In the case of HTML in-app messages, we need to make sure the WebView stops once the in-app message is removed.
    if (mInAppMessageView instanceof AppboyInAppMessageHtmlBaseView) {
      final AppboyInAppMessageHtmlBaseView inAppMessageHtmlBaseView = (AppboyInAppMessageHtmlBaseView) mInAppMessageView;
      if (inAppMessageHtmlBaseView.getMessageWebView() != null) {
        AppboyLogger.d(TAG, "Called destroy on the AppboyInAppMessageHtmlBaseView WebView");
        inAppMessageHtmlBaseView.getMessageWebView().destroy();
      }
    }
    mInAppMessageViewLifecycleListener.afterClosed(mInAppMessage);
  }

  /**
   * Sets all {@link View} children of the {@link FrameLayout} as {@link ViewCompat#IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}.
   */
  private static void setAllFrameLayoutChildrenAsNonAccessibilityImportant(FrameLayout frameLayout) {
    if (frameLayout == null) {
      AppboyLogger.w(TAG, "In-app message FrameLayout was null. Not preparing in-app message accessibility for exclusive mode.");
      return;
    }
    for (int i = 0; i < frameLayout.getChildCount(); i++) {
      View child = frameLayout.getChildAt(i);
      if (child != null) {
        ViewCompat.setImportantForAccessibility(child, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      }
    }
  }

  /**
   * Sets all {@link View} children of the {@link FrameLayout} as {@link ViewCompat#IMPORTANT_FOR_ACCESSIBILITY_AUTO}.
   */
  private static void setAllFrameLayoutChildrenAsAccessibilityAuto(FrameLayout frameLayout) {
    if (frameLayout == null) {
      AppboyLogger.w(TAG, "In-app message FrameLayout was null. Not preparing in-app message accessibility for exclusive mode.");
      return;
    }
    for (int i = 0; i < frameLayout.getChildCount(); i++) {
      View child = frameLayout.getChildAt(i);
      if (child != null) {
        ViewCompat.setImportantForAccessibility(child, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      }
    }
  }
}