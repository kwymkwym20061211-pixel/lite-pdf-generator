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

import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import android.app.AlertDialog;


public class CropFragment extends Fragment{
    
    /**
     * クロップ画面の確定コールバック。「完了」ボタンでのみ呼ばれる。「✕」では呼ばれない。
     *
     * <p>{@code rotations[i]} は EXIF + ユーザー手動回転の合計角度 (0/90/180/270)。
     * クロップ画面でその画像を一度も表示しなかった場合は -1 のまま渡る。
     * 受け取り側は -1 の場合に自分で EXIF を読むこと。</p>
     *
     * <p>{@code results[i]} の座標は「回転後フルサイズ」座標系。
     * PDF 生成時は回転適用済み Bitmap に対してクロップを適用すること。</p>
     */
    public interface OnCropDoneListener{
        void onCropDone(List<Uri> uris, PdfBuilder.CropPoints[] results, int[] rotations);
    }
    
    
    private static final float[] ZOOM_LEVELS = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    
    private List<Uri> mUris;
    private PdfBuilder.CropPoints[] mExistingCropPoints;
    private OnCropDoneListener mListener;
    
    private CropView mCropView;
    private TextView mTvIndex;
    private Button mBtnPrev, mBtnNext, mBtnDone, mBtnBack, mBtnDelete;
    private Button mBtnRotateL, mBtnRotateR;
    private Button[] mZoomBtns;

    private int mCurrentIndex = 0;
    private int mZoomIndex = 0;
    private PdfBuilder.CropPoints[] mResults;
    private Bitmap mCurrentBitmap;
    /*
     * mFileW/mFileH: ファイルから読んだ生寸法 (回転前)。
     * 90°/270° 回転後の実効寸法は effectiveW/H() で取得すること。
     * CropView が扱う座標は「回転済みプレビュー Bitmap」上の座標であり、
     * mResults に保存する座標は「回転済みフルサイズ」座標である。
     * この2種類の座標を混同しないこと。
     */
    private int[] mFileW, mFileH;
    /* EXIF + ユーザー手動回転の合計 (度)。-1 = その画像をまだ表示していないため未読 */
    private int[] mRotation;
    
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
        mBtnDelete = v.findViewById(R.id.btn_crop_delete);
        mBtnRotateL = v.findViewById(R.id.btn_rotate_l);
        mBtnRotateR = v.findViewById(R.id.btn_rotate_r);
        
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
        mFileW = new int[mUris.size()];
        mFileH = new int[mUris.size()];
        mRotation = new int[mUris.size()];
        Arrays.fill(mRotation, -1);
        
        /* ✕ はコールバックを呼ばずに閉じる → MainActivity の状態は変わらない (削除も無効) */
        mBtnBack.setOnClickListener(vv -> {
            if(mCurrentBitmap != null) mCurrentBitmap.recycle();
            getFragmentManager().beginTransaction().remove(this).commit();
        });
        mBtnPrev.setOnClickListener(vv -> navigate(-1));
        mBtnNext.setOnClickListener(vv -> navigate(+1));
        mBtnDone.setOnClickListener(vv -> finish());
        mBtnDelete.setOnClickListener(vv -> confirmDelete());
        mBtnRotateL.setOnClickListener(vv -> rotateCurrent(-90));
        mBtnRotateR.setOnClickListener(vv -> rotateCurrent(+90));
        
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
        
        /*
         * CropView の座標系 = 「回転済みプレビュー Bitmap」ピクセル座標。
         * mResults に保存する座標系 = 「回転済みフルサイズ」ピクセル座標。
         * effectiveW/H は 90°/270° 時に mFileW と mFileH を入れ替えて返す。
         */
        if(mCurrentBitmap != null && effectiveW(mCurrentIndex) > 0){
            float sx = (float) effectiveW(mCurrentIndex) / mCurrentBitmap.getWidth();
            float sy = (float) effectiveH(mCurrentIndex) / mCurrentBitmap.getHeight();
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

        /* 生ファイル寸法を取得 (初回のみ) */
        if(mFileW[index] == 0){
            try(InputStream is = getActivity().getContentResolver().openInputStream(uri)){
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                mFileW[index] = opts.outWidth;
                mFileH[index] = opts.outHeight;
            } catch(Exception ignored){}
        }

        /* 初回のみ EXIF 回転を読み取る */
        if(mRotation[index] < 0){
            mRotation[index] = readExifRotation(uri);
        }

        /* プレビュー用に縮小デコード (生 = 未回転) */
        int sample = calcSampleSize(mFileW[index], mFileH[index]);
        Bitmap rawBitmap = null;
        try(InputStream is = getActivity().getContentResolver().openInputStream(uri)){
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = sample;
            rawBitmap = BitmapFactory.decodeStream(is, null, opts);
        } catch(Exception ignored){}

        /* 合計回転を適用 */
        mCurrentBitmap = applyRotation(rawBitmap, mRotation[index]);

        mCropView.setBitmap(mCurrentBitmap);
        mCropView.setZoomMultiplier(ZOOM_LEVELS[mZoomIndex]);

        /* saveCurrent() の逆: フルサイズ座標 → プレビュー座標にスケールダウンして CropView に渡す */
        PdfBuilder.CropPoints saved = mResults[index];
        if(saved != null && mCurrentBitmap != null && effectiveW(index) > 0){
            float sx = (float) mCurrentBitmap.getWidth() / effectiveW(index);
            float sy = (float) mCurrentBitmap.getHeight() / effectiveH(index);
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
        if(mListener != null) mListener.onCropDone(new ArrayList<>(mUris), mResults, mRotation.clone());
        getFragmentManager().beginTransaction().remove(this).commit();
    }

    private void confirmDelete(){
        new AlertDialog.Builder(getActivity())
                .setMessage((mCurrentIndex + 1) + " 枚目の画像を削除しますか？")
                .setPositiveButton("削除", (d, w) -> deleteCurrentImage())
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void deleteCurrentImage(){
        int delIdx = mCurrentIndex;

        mUris.remove(delIdx);

        int newSize = mUris.size();
        PdfBuilder.CropPoints[] newResults  = new PdfBuilder.CropPoints[newSize];
        int[] newFileW    = new int[newSize];
        int[] newFileH    = new int[newSize];
        int[] newRotation = new int[newSize];
        for(int i = 0, j = 0; i < newSize + 1; i++){
            if(i == delIdx) continue;
            newResults[j]  = mResults[i];
            newFileW[j]    = mFileW[i];
            newFileH[j]    = mFileH[i];
            newRotation[j] = mRotation[i];
            j++;
        }
        mResults  = newResults;
        mFileW    = newFileW;
        mFileH    = newFileH;
        mRotation = newRotation;

        if(mCurrentBitmap != null){
            mCurrentBitmap.recycle();
            mCurrentBitmap = null;
        }

        if(mUris.isEmpty()){
            if(mListener != null) mListener.onCropDone(new ArrayList<>(), new PdfBuilder.CropPoints[0], new int[0]);
            getFragmentManager().beginTransaction().remove(this).commit();
            return;
        }

        if(mCurrentIndex >= mUris.size()) mCurrentIndex = mUris.size() - 1;
        loadImage(mCurrentIndex);
    }
    
    private void rotateCurrent(int delta){
        mRotation[mCurrentIndex] = (mRotation[mCurrentIndex] + delta + 360) % 360;
        Bitmap rotated = applyRotation(mCurrentBitmap, delta);
        if(rotated != mCurrentBitmap) mCurrentBitmap.recycle(); // applyRotation は新 Bitmap を返す
        mCurrentBitmap = rotated;
        mCropView.setBitmap(mCurrentBitmap);
        mCropView.resetPoints();
        /*
         * 保存済みクロップ座標は回転前の座標系で記録されているため、
         * 回転後に再利用すると正しい位置を指さない。破棄してリセットする。
         */
        mResults[mCurrentIndex] = null;
    }

    /**
     * 回転適用後の画像幅。90°/270° 回転時は W と H が入れ替わるため mFileH を返す。
     * saveCurrent() と loadImage() のスケール計算で必ず使うこと。直接 mFileW を参照しないこと。
     */
    private int effectiveW(int idx){
        return (mRotation[idx] % 180 == 0) ? mFileW[idx] : mFileH[idx];
    }

    /** 回転適用後の画像高さ。effectiveW() と対になる。 */
    private int effectiveH(int idx){
        return (mRotation[idx] % 180 == 0) ? mFileH[idx] : mFileW[idx];
    }

    private int readExifRotation(Uri uri){
        try(InputStream is = getActivity().getContentResolver().openInputStream(uri)){
            if(is == null) return 0;
            ExifInterface exif = new ExifInterface(is);
            switch(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                        ExifInterface.ORIENTATION_NORMAL)){
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch(Exception e){ return 0; }
    }

    private static Bitmap applyRotation(Bitmap src, int degrees){
        if(src == null || degrees % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
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