/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.text;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import android.graphics.Typeface;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

import com.facebook.csslayout.CSSConstants;
import com.facebook.csslayout.CSSNode;
import com.facebook.csslayout.MeasureOutput;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.uimanager.CSSColorUtil;
import com.facebook.react.uimanager.CatalystStylesDiffMap;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ReactShadowNode;
import com.facebook.react.uimanager.UIViewOperationQueue;
import com.facebook.react.uimanager.ViewDefaults;
import com.facebook.react.uimanager.ViewProps;

/**
 * {@link ReactShadowNode} class for spannable text view.
 *
 * This node calculates {@link Spannable} based on subnodes of the same type and passes the
 * resulting object down to textview's shadowview and actual native {@link TextView} instance.
 * It is important to keep in mind that {@link Spannable} is calculated only on layout step, so if
 * there are any text properties that may/should affect the result of {@link Spannable} they should
 * be set in a corresponding {@link ReactTextShadowNode}. Resulting {@link Spannable} object is then
 * then passed as "computedDataFromMeasure" down to shadow and native view.
 *
 * TODO(7255858): Rename *CSSNode to *ShadowView (or sth similar) as it's no longer is used
 * solely for layouting
 */
public class ReactTextShadowNode extends ReactShadowNode {

  public static final String PROP_TEXT = "text";
  public static final int UNSET = -1;

  private static final TextPaint sTextPaintInstance = new TextPaint();

  static {
    sTextPaintInstance.setFlags(TextPaint.ANTI_ALIAS_FLAG);
  }

  private static class SetSpanOperation {
    protected int start, end;
    protected Object what;
    SetSpanOperation(int start, int end, Object what) {
      this.start = start;
      this.end = end;
      this.what = what;
    }
    public void execute(SpannableStringBuilder sb) {
      sb.setSpan(what, start, end, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
    }
  }

  private static final void buildSpannedFromTextCSSNode(
      ReactTextShadowNode textCSSNode,
      SpannableStringBuilder sb,
      List<SetSpanOperation> ops) {
    int start = sb.length();
    if (textCSSNode.mText != null) {
      sb.append(textCSSNode.mText);
    }
    for (int i = 0, length = textCSSNode.getChildCount(); i < length; i++) {
      CSSNode child = textCSSNode.getChildAt(i);
      if (child instanceof ReactTextShadowNode) {
        buildSpannedFromTextCSSNode((ReactTextShadowNode) child, sb, ops);
      } else {
        throw new IllegalViewOperationException("Unexpected view type nested under text node: "
            + child.getClass());
      }
      ((ReactTextShadowNode) child).markUpdateSeen();
    }
    int end = sb.length();
    if (end > start) {
      if (textCSSNode.mIsColorSet) {
        ops.add(new SetSpanOperation(start, end, new ForegroundColorSpan(textCSSNode.mColor)));
      }
      if (textCSSNode.mIsBackgroundColorSet) {
        ops.add(
            new SetSpanOperation(
                start,
                end,
                new BackgroundColorSpan(textCSSNode.mBackgroundColor)));
      }
      if (textCSSNode.mFontSize != UNSET) {
        ops.add(new SetSpanOperation(start, end, new AbsoluteSizeSpan(textCSSNode.mFontSize)));
      }
      if (textCSSNode.mFontStyle != UNSET ||
          textCSSNode.mFontWeight != UNSET ||
          textCSSNode.mFontFamily != null) {
        ops.add(new SetSpanOperation(
                start,
                end,
                new CustomStyleSpan(
                    textCSSNode.mFontStyle,
                    textCSSNode.mFontWeight,
                    textCSSNode.mFontFamily)));
      }
      ops.add(new SetSpanOperation(start, end, new ReactTagSpan(textCSSNode.getReactTag())));
    }
  }

  protected static final Spanned fromTextCSSNode(ReactTextShadowNode textCSSNode) {
    SpannableStringBuilder sb = new SpannableStringBuilder();
    // TODO(5837930): Investigate whether it's worth optimizing this part and do it if so

    // The {@link SpannableStringBuilder} implementation require setSpan operation to be called
    // up-to-bottom, otherwise all the spannables that are withing the region for which one may set
    // a new spannable will be wiped out
    List<SetSpanOperation> ops = new ArrayList<SetSpanOperation>();
    buildSpannedFromTextCSSNode(textCSSNode, sb, ops);
    if (textCSSNode.mFontSize == -1) {
      sb.setSpan(
          new AbsoluteSizeSpan((int) Math.ceil(PixelUtil.toPixelFromSP(ViewDefaults.FONT_SIZE_SP))),
          0,
          sb.length(),
          Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
    }
    for (int i = ops.size() - 1; i >= 0; i--) {
      SetSpanOperation op = ops.get(i);
      op.execute(sb);
    }
    return sb;
  }

  private static final CSSNode.MeasureFunction TEXT_MEASURE_FUNCTION =
      new CSSNode.MeasureFunction() {
        @Override
        public void measure(CSSNode node, float width, MeasureOutput measureOutput) {
          // TODO(5578671): Handle text direction (see View#getTextDirectionHeuristic)
          ReactTextShadowNode reactCSSNode = (ReactTextShadowNode) node;
          TextPaint textPaint = sTextPaintInstance;
          Layout layout;
          Spanned text = Assertions.assertNotNull(
              reactCSSNode.mPreparedSpannedText,
              "Spannable element has not been prepared in onBeforeLayout");
          BoringLayout.Metrics boring = BoringLayout.isBoring(text, textPaint);
          float desiredWidth = boring == null ?
              Layout.getDesiredWidth(text, textPaint) : Float.NaN;

          if (boring == null &&
              (CSSConstants.isUndefined(width) ||
                  (!CSSConstants.isUndefined(desiredWidth) && desiredWidth <= width))) {
            // Is used when the width is not known and the text is not boring, ie. if it contains
            // unicode characters.
            layout = new StaticLayout(
                text,
                textPaint,
                (int) Math.ceil(desiredWidth),
                Layout.Alignment.ALIGN_NORMAL,
                1,
                0,
                true);
          } else if (boring != null && (CSSConstants.isUndefined(width) || boring.width <= width)) {
            // Is used for single-line, boring text when the width is either unknown or bigger
            // than the width of the text.
            layout = BoringLayout.make(
                text,
                textPaint,
                boring.width,
                Layout.Alignment.ALIGN_NORMAL,
                1,
                0,
                boring,
                true);
          } else  {
            // Is used for multiline, boring text and the width is known.
            layout = new StaticLayout(
                text,
                textPaint,
                (int) width,
                Layout.Alignment.ALIGN_NORMAL,
                1,
                0,
                true);
          }

          measureOutput.height = layout.getHeight();
          measureOutput.width = layout.getWidth();
          if (reactCSSNode.mNumberOfLines != UNSET &&
              reactCSSNode.mNumberOfLines < layout.getLineCount()) {
            measureOutput.height = layout.getLineBottom(reactCSSNode.mNumberOfLines - 1);
          }
          if (reactCSSNode.mLineHeight != UNSET) {
            int lines = reactCSSNode.mNumberOfLines != UNSET
                ? Math.min(reactCSSNode.mNumberOfLines, layout.getLineCount())
                : layout.getLineCount();
            float lineHeight = PixelUtil.toPixelFromSP(reactCSSNode.mLineHeight);
            measureOutput.height = lineHeight * lines;
          }
        }
      };

  /**
   * Return -1 if the input string is not a valid numeric fontWeight (100, 200, ..., 900), otherwise
   * return the weight.
   */
  private static int parseNumericFontWeight(String fontWeightString) {
    // This should be much faster than using regex to verify input and Integer.parseInt
    return fontWeightString.length() == 3 && fontWeightString.endsWith("00")
        && fontWeightString.charAt(0) <= '9' && fontWeightString.charAt(0) >= '1' ?
        100 * (fontWeightString.charAt(0) - '0') : -1;
  }

  private int mLineHeight = UNSET;
  private int mNumberOfLines = UNSET;
  private boolean mIsColorSet = false;
  private int mColor;
  private boolean mIsBackgroundColorSet = false;
  private int mBackgroundColor;
  private int mFontSize = UNSET;
  /**
   * mFontStyle can be {@link Typeface#NORMAL} or {@link Typeface#ITALIC}.
   * mFontWeight can be {@link Typeface#NORMAL} or {@link Typeface#BOLD}.
   */
  private int mFontStyle = UNSET;
  private int mFontWeight = UNSET;
  /**
   * NB: If a font family is used that does not have a style in a certain Android version (ie.
   * monospace bold pre Android 5.0), that style (ie. bold) will not be inherited by nested Text
   * nodes. To retain that style, you have to add it to those nodes explicitly.
   * Example, Android 4.4:
   * <Text style={{fontFamily="serif" fontWeight="bold"}}>Bold Text</Text>
   *   <Text style={{fontFamily="sans-serif"}}>Bold Text</Text>
   *     <Text style={{fontFamily="serif}}>Bold Text</Text>
   *
   * <Text style={{fontFamily="monospace" fontWeight="bold"}}>Not Bold Text</Text>
   *   <Text style={{fontFamily="sans-serif"}}>Not Bold Text</Text>
   *     <Text style={{fontFamily="serif}}>Not Bold Text</Text>
   *
   * <Text style={{fontFamily="monospace" fontWeight="bold"}}>Not Bold Text</Text>
   *   <Text style={{fontFamily="sans-serif" fontWeight="bold"}}>Bold Text</Text>
   *     <Text style={{fontFamily="serif}}>Bold Text</Text>
   */
  private @Nullable String mFontFamily = null;
  private @Nullable String mText = null;

  private @Nullable Spanned mPreparedSpannedText;
  private final boolean mIsVirtual;

  @Override
  public void onBeforeLayout() {
    if (mIsVirtual) {
      return;
    }
    mPreparedSpannedText = fromTextCSSNode(this);
    markUpdated();
  }

  @Override
  protected void markUpdated() {
    super.markUpdated();
    // We mark virtual anchor node as dirty as updated text needs to be re-measured
    if (!mIsVirtual) {
      super.dirty();
    }
  }

  @Override
  public void updateProperties(CatalystStylesDiffMap styles) {
    super.updateProperties(styles);

    if (styles.hasKey(PROP_TEXT)) {
      mText = styles.getString(PROP_TEXT);
      markUpdated();
    }
    if (styles.hasKey(ViewProps.NUMBER_OF_LINES)) {
      mNumberOfLines = styles.getInt(ViewProps.NUMBER_OF_LINES, UNSET);
      markUpdated();
    }
    if (styles.hasKey(ViewProps.LINE_HEIGHT)) {
      mLineHeight = styles.getInt(ViewProps.LINE_HEIGHT, UNSET);
      markUpdated();
    }
    if (styles.hasKey(ViewProps.FONT_SIZE)) {
      if (styles.isNull(ViewProps.FONT_SIZE)) {
        mFontSize = UNSET;
      } else {
        mFontSize = (int) Math.ceil(PixelUtil.toPixelFromSP(
            styles.getFloat(ViewProps.FONT_SIZE, ViewDefaults.FONT_SIZE_SP)));
      }
      markUpdated();
    }
    if (styles.hasKey(ViewProps.COLOR)) {
      String colorString = styles.getString(ViewProps.COLOR);
      if (colorString == null) {
        mIsColorSet = false;
      } else {
        mColor = CSSColorUtil.getColor(colorString);
        mIsColorSet = true;
      }
      markUpdated();
    }
    if (styles.hasKey(ViewProps.BACKGROUND_COLOR)) {
      String colorString = styles.getString(ViewProps.BACKGROUND_COLOR);
      if (colorString == null) {
        mIsBackgroundColorSet = false;
      } else {
        mBackgroundColor = CSSColorUtil.getColor(colorString);
        mIsBackgroundColorSet = true;
      }
      markUpdated();
    }

    if (styles.hasKey(ViewProps.FONT_FAMILY)) {
      mFontFamily = styles.getString(ViewProps.FONT_FAMILY);
      markUpdated();
    }

    if (styles.hasKey(ViewProps.FONT_WEIGHT)) {
      String fontWeightString = styles.getString(ViewProps.FONT_WEIGHT);
      int fontWeightNumeric = fontWeightString != null ?
          parseNumericFontWeight(fontWeightString) : -1;
      int fontWeight = UNSET;
      if (fontWeightNumeric >= 500 || "bold".equals(fontWeightString)) {
        fontWeight = Typeface.BOLD;
      } else if ("normal".equals(fontWeightString) ||
          (fontWeightNumeric != -1 && fontWeightNumeric < 500)) {
        fontWeight = Typeface.NORMAL;
      }
      if (fontWeight != mFontWeight) {
        mFontWeight = fontWeight;
        markUpdated();
      }
    }

    if (styles.hasKey(ViewProps.FONT_STYLE)) {
      String fontStyleString = styles.getString(ViewProps.FONT_STYLE);
      int fontStyle = UNSET;
      if ("italic".equals(fontStyleString)) {
        fontStyle = Typeface.ITALIC;
      } else if ("normal".equals(fontStyleString)) {
        fontStyle = Typeface.NORMAL;
      }
      if (fontStyle != mFontStyle) {
        mFontStyle = fontStyle;
        markUpdated();
      }
    }
  }

  @Override
  public boolean isVirtualAnchor() {
    return !mIsVirtual;
  }

  @Override
  public boolean isVirtual() {
    return mIsVirtual;
  }

  @Override
  public void onCollectExtraUpdates(UIViewOperationQueue uiViewOperationQueue) {
    if (mIsVirtual) {
      return;
    }
    super.onCollectExtraUpdates(uiViewOperationQueue);
    if (mPreparedSpannedText != null) {
      uiViewOperationQueue.enqueueUpdateExtraData(getReactTag(), mPreparedSpannedText);
    }
  }

  public ReactTextShadowNode(boolean isVirtual) {
    mIsVirtual = isVirtual;
    if (!isVirtual) {
      setMeasureFunction(TEXT_MEASURE_FUNCTION);
    }
  }
}
