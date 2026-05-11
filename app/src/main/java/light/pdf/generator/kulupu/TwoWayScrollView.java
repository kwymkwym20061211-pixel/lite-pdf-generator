package light.pdf.generator.kulupu;


import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.OverScroller;


/**
 * TwoWayScrollView
 * <p>
 * 縦横両方スクロールできる単純なコンテナ。
 * 子View1つのみ想定。
 * CropView からの requestDisallowInterceptTouchEvent に対応する。
 */
public class TwoWayScrollView extends ViewGroup{
    
    private final OverScroller mScroller;
    private float mLastX, mLastY;
    private boolean mDisallowIntercept = false;
    
    public TwoWayScrollView(Context ctx){this(ctx, null);}
    
    public TwoWayScrollView(Context ctx, AttributeSet attrs){
        super(ctx, attrs);
        mScroller = new OverScroller(ctx);
        setWillNotDraw(false);
        /* スクロールバーを有効にする */
        setHorizontalScrollBarEnabled(true);
        setVerticalScrollBarEnabled(true);
    }
    
    /* ---------------------------------------------------------------- */
    /* レイアウト                                                        */
    /* ---------------------------------------------------------------- */
    
    @Override
    protected void onMeasure(int widthSpec, int heightSpec){
        /* 子を無制限サイズで計測してコンテンツサイズを得る */
        if(getChildCount() > 0){
            View child = getChildAt(0);
            measureChild(child,
                         MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                         MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        /* 自分自身は親から与えられたサイズ */
        setMeasuredDimension(
                resolveSize(0, widthSpec),
                resolveSize(0, heightSpec));
    }
    
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b){
        if(getChildCount() == 0) return;
        View child = getChildAt(0);
        child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
    }
    
    /* ---------------------------------------------------------------- */
    /* スクロール範囲                                                    */
    /* ---------------------------------------------------------------- */
    
    private int maxScrollX(){
        if(getChildCount() == 0) return 0;
        return Math.max(0, getChildAt(0).getMeasuredWidth() - getWidth());
    }
    
    private int maxScrollY(){
        if(getChildCount() == 0) return 0;
        return Math.max(0, getChildAt(0).getMeasuredHeight() - getHeight());
    }
    
    @Override
    protected int computeHorizontalScrollRange(){
        return getChildCount() > 0 ? getChildAt(0).getMeasuredWidth() : getWidth();
    }
    
    @Override
    protected int computeVerticalScrollRange(){
        return getChildCount() > 0 ? getChildAt(0).getMeasuredHeight() : getHeight();
    }
    
    @Override
    protected int computeHorizontalScrollOffset(){return getScrollX();}
    
    @Override
    protected int computeVerticalScrollOffset(){return getScrollY();}
    
    /* ---------------------------------------------------------------- */
    /* タッチ処理                                                        */
    /* ---------------------------------------------------------------- */
    
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow){
        mDisallowIntercept = disallow;
        super.requestDisallowInterceptTouchEvent(disallow);
    }
    
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e){
        if(mDisallowIntercept) return false;
        /* DOWN は子に届かせる。MOVE で初めて横取りする。 */
        if(e.getAction() == MotionEvent.ACTION_DOWN){
            mLastX = e.getX();
            mLastY = e.getY();
            return false;
        }
        if(e.getAction() == MotionEvent.ACTION_MOVE && !mDisallowIntercept){
            return true; /* スクロール開始 */
        }
        return false;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent e){
        if(mDisallowIntercept) return false;
        
        float x = e.getX(), y = e.getY();
        switch(e.getAction()){
            case MotionEvent.ACTION_DOWN:
                mLastX = x;
                mLastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = (int) (mLastX - x);
                int dy = (int) (mLastY - y);
                mLastX = x;
                mLastY = y;
                scrollBy(dx, dy);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }
    
    @Override
    public void scrollBy(int dx, int dy){
        scrollTo(
                clamp(getScrollX() + dx, 0, maxScrollX()),
                clamp(getScrollY() + dy, 0, maxScrollY())
                );
    }
    
    @Override
    public void scrollTo(int x, int y){
        super.scrollTo(clamp(x, 0, maxScrollX()), clamp(y, 0, maxScrollY()));
        awakenScrollBars();
    }
    
    private static int clamp(int val, int min, int max){
        return Math.max(min, Math.min(max, val));
    }
}