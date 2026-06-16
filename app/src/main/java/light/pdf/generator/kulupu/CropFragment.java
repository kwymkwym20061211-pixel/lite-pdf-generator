package light.pdf.generator.kulupu;


import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;


public class CropFragment extends Fragment{
    
    public interface OnCropDoneListener{
        void onCropDone(PdfBuilder.CropPoints[] results);
    }
    
    
    private static final float[] ZOOM_LEVELS = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    
    private List<Uri> mUris;
    private PdfBuilder.CropPoints[] mExistingCropPoints;
    private OnCropDoneListener mListener;
    
    private CropView mCropView;
    private TextView mTvIndex;
    private Button mBtnPrev, mBtnNext, mBtnDone, mBtnBack;
    private Button[] mZoomBtns;
    
    private int mCurrentIndex = 0;
    private int mZoomIndex = 0;
    private PdfBuilder.CropPoints[] mResults;
    private Bitmap mCurrentBitmap;
    /* フルサイズの画像サイズ (クロップ座標をスケール戻しするため) */
    private int[] mFullW, mFullH;
    
    public static CropFragment newInstance(List<Uri> uris, PdfBuilder.CropPoints[] existing, OnCropDoneListener listener){
        CropFragment f = new CropFragment();
        f.mUris = uris;
        f.mExistingCropPoints = existing;
        f.mListener = listener;
        return f;
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState){
        View v = inflater.inflate(R.layout.fragment_crop, container, false);
        
        mCropView = v.findViewById(R.id.crop_view);
        mTvIndex = v.findViewById(R.id.tv_crop_index);
        mBtnBack = v.findViewById(R.id.btn_crop_back);
        mBtnPrev = v.findViewById(R.id.btn_crop_prev);
        mBtnNext = v.findViewById(R.id.btn_crop_next);
        mBtnDone = v.findViewById(R.id.btn_crop_done);
        
        mZoomBtns = new Button[]{
                v.findViewById(R.id.btn_zoom_1),
                v.findViewById(R.id.btn_zoom_2),
                v.findViewById(R.id.btn_zoom_3),
                v.findViewById(R.id.btn_zoom_4),
                v.findViewById(R.id.btn_zoom_5),
        };
        
        mResults = new PdfBuilder.CropPoints[mUris.size()];
        if(mExistingCropPoints != null){
            int len = Math.min(mExistingCropPoints.length, mResults.length);
            System.arraycopy(mExistingCropPoints, 0, mResults, 0, len);
        }
        mFullW = new int[mUris.size()];
        mFullH = new int[mUris.size()];
        
        mBtnBack.setOnClickListener(vv -> {
            if(mCurrentBitmap != null) mCurrentBitmap.recycle();
            getFragmentManager().beginTransaction().remove(this).commit();
        });
        mBtnPrev.setOnClickListener(vv -> navigate(-1));
        mBtnNext.setOnClickListener(vv -> navigate(+1));
        mBtnDone.setOnClickListener(vv -> finish());
        
        for(int i = 0; i < mZoomBtns.length; i++){
            final int idx = i;
            mZoomBtns[i].setOnClickListener(vv -> setZoom(idx));
        }
        
        loadImage(0);
        return v;
    }
    
    /* ---------------------------------------------------------------- */
    
    private void setZoom(int idx){
        mZoomIndex = idx;
        mCropView.setZoomMultiplier(ZOOM_LEVELS[idx]);
        updateZoomButtons();
    }
    
    private void updateZoomButtons(){
        for(int i = 0; i < mZoomBtns.length; i++){
            mZoomBtns[i].setAlpha(i == mZoomIndex ? 1.0f : 0.5f);
        }
    }
    
    private void navigate(int delta){
        saveCurrent();
        int next = mCurrentIndex + delta;
        if(next < 0 || next >= mUris.size()) return;
        loadImage(next);
    }
    
    private void saveCurrent(){
        if(mCropView == null) return;
        PdfBuilder.CropPoints cp = mCropView.getCropPoints();
        if(cp == null) return;
        
        /* CropView はプレビュー縮小 Bitmap 上の座標を返すので
         * フルサイズにスケールアップして保存する */
        if(mCurrentBitmap != null && mFullW[mCurrentIndex] > 0){
            float sx = (float) mFullW[mCurrentIndex] / mCurrentBitmap.getWidth();
            float sy = (float) mFullH[mCurrentIndex] / mCurrentBitmap.getHeight();
            for(int i = 0; i < 4; i++){
                cp.x[i] *= sx;
                cp.y[i] *= sy;
            }
        }
        mResults[mCurrentIndex] = cp;
    }
    
    private void loadImage(int index){
        mCurrentIndex = index;
        
        if(mCurrentBitmap != null){
            mCurrentBitmap.recycle();
            mCurrentBitmap = null;
        }
        
        Uri uri = mUris.get(index);
        
        /* フルサイズを先に取得 */
        if(mFullW[index] == 0){
            try(InputStream is = getActivity().getContentResolver().openInputStream(uri)){
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                mFullW[index] = opts.outWidth;
                mFullH[index] = opts.outHeight;
            } catch(Exception ignored){}
        }
        
        /* プレビュー用に縮小デコード */
        int sample = calcSampleSize(mFullW[index], mFullH[index]);
        try(InputStream is = getActivity().getContentResolver().openInputStream(uri)){
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = sample;
            mCurrentBitmap = BitmapFactory.decodeStream(is, null, opts);
        } catch(Exception ignored){}
        
        mCropView.setBitmap(mCurrentBitmap);
        mCropView.setZoomMultiplier(ZOOM_LEVELS[mZoomIndex]);

        PdfBuilder.CropPoints saved = mResults[index];
        if(saved != null && mCurrentBitmap != null && mFullW[index] > 0){
            float sx = (float) mCurrentBitmap.getWidth() / mFullW[index];
            float sy = (float) mCurrentBitmap.getHeight() / mFullH[index];
            float[] px = new float[4], py = new float[4];
            for(int i = 0; i < 4; i++){
                px[i] = saved.x[i] * sx;
                py[i] = saved.y[i] * sy;
            }
            mCropView.setPoints(px, py);
        } else {
            mCropView.resetPoints();
        }
        
        updateNavButtons();
        updateZoomButtons();
        mTvIndex.setText((index + 1) + " / " + mUris.size());
    }
    
    private void finish(){
        saveCurrent();
        if(mCurrentBitmap != null) mCurrentBitmap.recycle();
        if(mListener != null) mListener.onCropDone(mResults);
        getFragmentManager().beginTransaction().remove(this).commit();
    }
    
    private void updateNavButtons(){
        mBtnPrev.setEnabled(mCurrentIndex > 0);
        mBtnNext.setEnabled(mCurrentIndex < mUris.size() - 1);
    }
    
    private static int calcSampleSize(int fullW, int fullH){
        int maxDim = Math.max(fullW, fullH);
        int sample = 1;
        while(maxDim / (sample * 2) > 1500) sample *= 2;
        return sample;
    }
}