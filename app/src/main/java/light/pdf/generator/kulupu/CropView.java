package light.pdf.generator.kulupu;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.View;


/**
 * CropView
 * <p>
 * ズームレベルは外部から setZoomMultiplier() で設定する。
 * 1x = 画像全体がView(padding除く)に収まるFitスケール。
 * 2x/3x/... = Fitスケールの倍数。
 * <p>
 * スクロールは外側の NoTouchScrollView / NoTouchHorizontalScrollView が担う。
 * タッチイベントはハンドルドラッグのみ処理する。
 */
public class CropView extends View{
    
    private static final float HANDLE_RADIUS_DP = 20f;
    private static final float LINE_WIDTH_DP = 2f;
    
    private Bitmap mBitmap;
    
    /* ズーム: zoomMultiplier=1 のとき fitScale を使う */
    private float mZoomMultiplier = 1.0f;
    private float mFitScale = 1.0f; /* View サイズから計算、一度だけ確定 */
    private boolean mFitScaleFixed = false;
    
    /* 4点の Bitmap ピクセル座標 */
    private final float[] mPx = new float[4];
    private final float[] mPy = new float[4];
    private boolean mPointsInitialized = false;
    
    private int mDragging = -1;
    
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mHandleRadius;
    
    public CropView(Context ctx){this(ctx, null);}
    
    public CropView(Context ctx, AttributeSet attrs){
        super(ctx, attrs);
        float density = ctx.getResources().getDisplayMetrics().density;
        mHandleRadius = HANDLE_RADIUS_DP * density;
        
        mLinePaint.setColor(Color.WHITE);
        mLinePaint.setStrokeWidth(LINE_WIDTH_DP * density);
        mLinePaint.setStyle(Paint.Style.STROKE);
        
        mHandlePaint.setColor(Color.WHITE);
        mHandlePaint.setStyle(Paint.Style.FILL);
        
        mFillPaint.setColor(0x33FFFFFF);
        mFillPaint.setStyle(Paint.Style.FILL);
    }
    
    /* ---------------------------------------------------------------- */
    /* 公開 API                                                          */
    /* ---------------------------------------------------------------- */
    
    public void setBitmap(Bitmap bmp){
        mBitmap = bmp;
        mPointsInitialized = false;
        mFitScaleFixed = false;  /* 新しい画像で再計算 */
        recalcFitScale();
        requestLayout();
        invalidate();
    }
    
    /**
     * ズーム倍率を設定する。
     * 1.0f = 画像横幅がスクリーン幅にfitするサイズ。
     * 2.0f = その2倍、など。
     * 点座標 (Bitmapピクセル座標) は変更しない。
     * fitScale を再計算してからレイアウトし直す。
     */
    public void setZoomMultiplier(float multiplier){
        mZoomMultiplier = multiplier;
        /* fitScale を再計算 (倍率変更のたびにリフレッシュ) */
        mFitScaleFixed = false;
        recalcFitScale();
        requestLayout();
        invalidate();
    }
    
    public void resetPoints(){
        if(mBitmap == null) return;
        mPx[0] = 0;
        mPy[0] = 0;
        mPx[1] = mBitmap.getWidth() - 1;
        mPy[1] = 0;
        mPx[2] = mBitmap.getWidth() - 1;
        mPy[2] = mBitmap.getHeight() - 1;
        mPx[3] = 0;
        mPy[3] = mBitmap.getHeight() - 1;
        mPointsInitialized = true;
        invalidate();
    }

    /**
     * 保存済み座標を復元する。座標はプレビュー Bitmap ピクセル座標であること。
     * フルサイズ座標をそのまま渡すと表示が壊れる (CropFragment.loadImage() でスケール変換してから呼ぶ)。
     */
    public void setPoints(float[] px, float[] py){
        System.arraycopy(px, 0, mPx, 0, 4);
        System.arraycopy(py, 0, mPy, 0, 4);
        mPointsInitialized = true;
        invalidate();
    }

    /**
     * 現在の4点座標をプレビュー Bitmap ピクセル座標で返す。
     * フルサイズ座標に変換するには CropFragment.saveCurrent() の scale 処理が必要。
     */
    public PdfBuilder.CropPoints getCropPoints(){
        if(mBitmap == null) return null;
        PdfBuilder.CropPoints cp = new PdfBuilder.CropPoints();
        int w = mBitmap.getWidth(), h = mBitmap.getHeight();
        for(int i = 0; i < 4; i++){
            cp.x[i] = Math.max(0, Math.min(w - 1, mPx[i]));
            cp.y[i] = Math.max(0, Math.min(h - 1, mPy[i]));
        }
        return cp;
    }
    
    /* ---------------------------------------------------------------- */
    /* 内部: スケール計算                                                */
    /* ---------------------------------------------------------------- */
    
    /**
     * fitScale を計算する。一度確定したら以後変更しない。
     * 基準: 画像横幅がスクリーン幅 (padding 除く) にぴったり収まるスケール。
     * TwoWayScrollView 配下では getWidth()==0 になるため
     * スクリーンの物理ピクセル幅を直接取得して使う。
     */
    private void recalcFitScale(){
        if(mFitScaleFixed) return;
        if(mBitmap == null){
            mFitScale = 1.0f;
            return;
        }
        
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getContext()
                .getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(dm);
        int screenW = dm.widthPixels;
        
        int availW = screenW - getPaddingLeft() - getPaddingRight();
        if(availW <= 0) return;
        
        mFitScale = (float) availW / mBitmap.getWidth();
        mFitScaleFixed = true;
    }
    
    /**
     * 現在の実効スケール = fitScale × zoomMultiplier
     */
    private float effectiveScale(){
        return mFitScale * mZoomMultiplier;
    }
    
    /* Bitmap ピクセル → View 座標 */
    private float toViewX(float bx){return getPaddingLeft() + bx * effectiveScale();}
    
    private float toViewY(float by){return getPaddingTop() + by * effectiveScale();}
    
    /* View 座標 → Bitmap ピクセル */
    private float toBitmapX(float vx){return (vx - getPaddingLeft()) / effectiveScale();}
    
    private float toBitmapY(float vy){return (vy - getPaddingTop()) / effectiveScale();}
    
    /* ---------------------------------------------------------------- */
    /* レイアウト・描画                                                  */
    /* ---------------------------------------------------------------- */
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh){
        super.onSizeChanged(w, h, oldw, oldh);
        /* TwoWayScrollView 配下では w==0 の場合があるため
         * ここでは固定済みでなければ試みるだけにする */
        recalcFitScale();
        if(!mPointsInitialized && mBitmap != null) resetPoints();
    }
    
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b){
        super.onLayout(changed, l, t, r, b);
        /* onSizeChanged で未確定だった場合にここで再試行 */
        if(!mFitScaleFixed){
            recalcFitScale();
            if(!mPointsInitialized && mBitmap != null) resetPoints();
            if(mFitScaleFixed) requestLayout(); /* サイズを再計算 */
        }
    }
    
    @Override
    protected void onMeasure(int widthSpec, int heightSpec){
        if(mBitmap == null){
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        /* 1x のとき: ScrollView の利用可能サイズに合わせる (fitScale を計算するため)
         * まず親から提案されたサイズで fitScale を仮計算し、
         * そのうえでズーム後のコンテンツサイズを返す。        */
        /* fitScale は onSizeChanged/recalcFitScale で確定済み。ここでは触らない。 */
        
        float scale = effectiveScale();
        int contentW = (int) (mBitmap.getWidth() * scale + 0.5f);
        int contentH = (int) (mBitmap.getHeight() * scale + 0.5f);
        int totalW = contentW + getPaddingLeft() + getPaddingRight();
        int totalH = contentH + getPaddingTop() + getPaddingBottom();
        
        /* 1x のとき: スクリーンサイズを下回らないようにする。
         * TwoWayScrollView は UNSPECIFIED で渡すので MeasureSpec のサイズは
         * 使えない。スクリーンの物理ピクセルサイズを直接参照する。 */
        if(mZoomMultiplier <= 1.0f){
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getMetrics(dm);
            totalW = Math.max(totalW, dm.widthPixels);
            totalH = Math.max(totalH, dm.heightPixels);
        }
        
        setMeasuredDimension(totalW, totalH);
    }
    
    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(mBitmap == null) return;
        
        float scale = effectiveScale();
        float ox = getPaddingLeft();
        float oy = getPaddingTop();
        
        /* 画像描画 */
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);
        canvas.drawBitmap(mBitmap, 0, 0, null);
        canvas.restore();
        
        /* 4点を View 座標に変換 */
        float[] vx = new float[4], vy = new float[4];
        for(int i = 0; i < 4; i++){
            vx[i] = toViewX(mPx[i]);
            vy[i] = toViewY(mPy[i]);
        }
        
        Path path = new Path();
        path.moveTo(vx[0], vy[0]);
        path.lineTo(vx[1], vy[1]);
        path.lineTo(vx[2], vy[2]);
        path.lineTo(vx[3], vy[3]);
        path.close();
        canvas.drawPath(path, mFillPaint);
        canvas.drawPath(path, mLinePaint);
        
        for(int i = 0; i < 4; i++){
            canvas.drawCircle(vx[i], vy[i], mHandleRadius, mHandlePaint);
        }
    }
    
    /* ---------------------------------------------------------------- */
    /* タッチ処理 (ハンドルドラッグのみ)                                 */
    /* ---------------------------------------------------------------- */
    
    @Override
    public boolean onTouchEvent(MotionEvent e){
        float tx = e.getX(), ty = e.getY();
        switch(e.getAction()){
            case MotionEvent.ACTION_DOWN:
                mDragging = nearestHandle(tx, ty);
                if(mDragging >= 0){
                    /* スクロールビューへのイベント横取りを禁止 */
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if(mDragging >= 0){
                    mPx[mDragging] = clampBX(toBitmapX(tx));
                    mPy[mDragging] = clampBY(toBitmapY(ty));
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(mDragging >= 0){
                    getParent().requestDisallowInterceptTouchEvent(false);
                    mDragging = -1;
                    return true;
                }
                return false;
        }
        return false;
    }
    
    private int nearestHandle(float tx, float ty){
        float threshold = mHandleRadius * 2.5f;
        for(int i = 0; i < 4; i++){
            float dx = tx - toViewX(mPx[i]);
            float dy = ty - toViewY(mPy[i]);
            if(dx * dx + dy * dy <= threshold * threshold) return i;
        }
        return -1;
    }
    
    private float clampBX(float bx){
        return Math.max(0, Math.min(mBitmap != null ? mBitmap.getWidth() - 1 : 0, bx));
    }
    
    private float clampBY(float by){
        return Math.max(0, Math.min(mBitmap != null ? mBitmap.getHeight() - 1 : 0, by));
    }
}