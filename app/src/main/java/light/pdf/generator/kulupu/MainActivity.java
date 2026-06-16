package light.pdf.generator.kulupu;


import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Handler;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends Activity{
    
    private static final int REQ_PICK_IMAGES = 1;
    private static final int REQ_PICK_DIR = 2;
    
    private final List<Uri> mSelectedUris = new ArrayList<>();
    private PdfBuilder.CropPoints[] mCropPoints = null;
    private int[] mRotations = null;
    private ImageSorter.SortConfig mPendingSortConfig = null;
    
    private int mDstChannels = PdfBuilder.CHANNELS_GRAY;
    private int mBitsPerCh = PdfBuilder.BPC_4;
    
    private TextView mTvCount;
    private Button mBtnPick;
    // private Button      mBtnPickDir; /* ディレクトリ選択: 未実装 */
    private Button mBtnCrop;
    private Button mBtnGenerate;
    private ProgressBar mProgress;
    private TextView mTvStatus;
    
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    
    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mTvCount = findViewById(R.id.tv_count);
        mBtnPick = findViewById(R.id.btn_pick);
        // mBtnPickDir  = findViewById(R.id.btn_pick_dir);
        mBtnCrop = findViewById(R.id.btn_crop);
        mBtnGenerate = findViewById(R.id.btn_generate);
        mProgress = findViewById(R.id.progress);
        mTvStatus = findViewById(R.id.tv_status);
        
        mBtnPick.setOnClickListener(v -> pickImages());
        // mBtnPickDir.setOnClickListener(v -> pickDirectory());
        mBtnCrop.setOnClickListener(v -> openCrop());
        
        Spinner spinnerMode = findViewById(R.id.spinner_mode);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"グレースケール", "カラー (RGB)"});
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(modeAdapter);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id){
                mDstChannels = (pos == 0) ? PdfBuilder.CHANNELS_GRAY : PdfBuilder.CHANNELS_RGB;
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> p){}
        });
        
        Spinner spinnerBpc = findViewById(R.id.spinner_bpc);
        ArrayAdapter<String> bpcAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"1 bit", "2 bit", "4 bit", "8 bit"});
        bpcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBpc.setAdapter(bpcAdapter);
        spinnerBpc.setSelection(2);
        spinnerBpc.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            private final int[] BPC = {1, 2, 4, 8};
            
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id){
                mBitsPerCh = BPC[pos];
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> p){}
        });
        
        mBtnGenerate.setOnClickListener(v -> startGenerate());
        updateCount();
    }
    
    /* ---------------------------------------------------------------- */
    /* 画像選択                                                           */
    /* ---------------------------------------------------------------- */
    
    private void pickImages(){
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "画像を選択"), REQ_PICK_IMAGES);
    }
    
    private void pickDirectory(){
        DirectorySortDialog.newInstance(cfg -> {
            /* ソート設定を保持してディレクトリ選択を起動 */
            mPendingSortConfig = cfg;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O){
                Uri initialUri = DocumentsContract.buildRootUri(
                        "com.android.externalstorage.documents", "primary");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            }
            startActivityForResult(intent, REQ_PICK_DIR);
        }).show(getFragmentManager(), "sort");
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        super.onActivityResult(requestCode, resultCode, data);
        
        if(requestCode == REQ_PICK_DIR && resultCode == RESULT_OK && data != null){
            Uri treeUri = data.getData();
            ImageSorter.SortConfig cfg = mPendingSortConfig;
            mPendingSortConfig = null;
            if(treeUri == null || cfg == null) return;
            /* パーミッションを永続化しないと ContentResolver.query が空を返す */
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                             );
            /* バックグラウンドでディレクトリを列挙してリストに追加 */
            mExecutor.execute(() -> {
                List<Uri> uris = ImageSorter.listSorted(this, treeUri, cfg);
                mMain.post(() -> {
                    mSelectedUris.addAll(uris);
                    mCropPoints = null;
                    updateCount();
                    if(uris.isEmpty()){
                        android.widget.Toast.makeText(this,
                                                      "画像が見つかりませんでした",
                                                      android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            });
            return;
        }
        
        if(requestCode != REQ_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        
        mSelectedUris.clear();
        mCropPoints = null;
        mRotations  = null;
        
        if(data.getClipData() != null){
            int count = data.getClipData().getItemCount();
            for(int i = 0; i < count; i++)
                mSelectedUris.add(data.getClipData().getItemAt(i).getUri());
        } else if(data.getData() != null){
            mSelectedUris.add(data.getData());
        }
        updateCount();
    }
    
    /* ---------------------------------------------------------------- */
    /* クロップ設定                                                       */
    /* ---------------------------------------------------------------- */
    
    private void openCrop(){
        if(mSelectedUris.isEmpty()){
            Toast.makeText(this, "先に画像を選択してください", Toast.LENGTH_SHORT).show();
            return;
        }
        
        CropFragment fragment = CropFragment.newInstance(
                new ArrayList<>(mSelectedUris),
                mCropPoints,
                (uris, results, rotations) -> {
                    mSelectedUris.clear();
                    mSelectedUris.addAll(uris);
                    mCropPoints = results.length > 0 ? results : null;
                    mRotations  = rotations.length > 0 ? rotations : null;
                    updateCount();
                    Toast.makeText(this, "クロップ設定を保存しました", Toast.LENGTH_SHORT).show();
                }
                                                        );
        
        getFragmentManager()
                .beginTransaction()
                .add(android.R.id.content, fragment)
                .addToBackStack(null)
                .commit();
    }
    
    /* ---------------------------------------------------------------- */
    /* PDF 生成                                                           */
    /* ---------------------------------------------------------------- */
    
    private void startGenerate(){
        if(mSelectedUris.isEmpty()){
            Toast.makeText(this, "画像を選択してください", Toast.LENGTH_SHORT).show();
            return;
        }
        setUiEnabled(false);
        mProgress.setMax(mSelectedUris.size());
        mProgress.setProgress(0);
        mProgress.setVisibility(View.VISIBLE);
        mTvStatus.setText("変換中…");
        
        List<Uri> uris = new ArrayList<>(mSelectedUris);
        PdfBuilder.CropPoints[] crops = mCropPoints;
        int[] rots = mRotations;
        int dstCh = mDstChannels;
        int bpc = mBitsPerCh;
        String fileName = "board_"
                          + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                          + ".pdf";

        mExecutor.execute(() -> generate(uris, crops, rots, dstCh, bpc, fileName));
    }
    
    private void generate(List<Uri> uris, PdfBuilder.CropPoints[] crops,
                          int[] rotations, int dstCh, int bpc, String fileName){
        PdfBuilder builder = new PdfBuilder(this);
        try{
            builder.start(fileName);
            for(int i = 0; i < uris.size(); i++){
                int rot = (rotations != null && i < rotations.length) ? rotations[i] : -1;
                Bitmap bmp = decodeBitmap(uris.get(i), rot);
                if(bmp == null) throw new RuntimeException("画像の読み込み失敗: " + uris.get(i));
                
                PdfBuilder.CropPoints crop = (crops != null) ? crops[i] : null;
                /* bmp は decodeBitmap() で既に正立済み。crop 座標も正立後の座標系 */
                builder.appendImage(bmp, dstCh, bpc, crop);
                bmp.recycle();
                
                final int progress = i + 1;
                mMain.post(() -> mProgress.setProgress(progress));
            }
            String cachePath = builder.end();
            String savedName = saveToDownloads(cachePath, fileName);
            mMain.post(() -> onSuccess(savedName));
        } catch(Exception e){
            builder.abort();
            mMain.post(() -> onFailure(e.getMessage()));
        }
    }
    
    /**
     * 画像をデコードして回転済み Bitmap を返す。
     *
     * <p>{@code totalRotation} は CropFragment から受け取った合計回転角度 (EXIF + 手動)。
     * -1 の場合はクロップ画面でその画像を表示していないため、ここで EXIF を直接読んで適用する。
     * 0 以上の場合は既に合計値なので EXIF を再読しない (二重適用防止)。</p>
     *
     * <p>C コアは回転を行わないため、このメソッドが返す Bitmap は必ず正立していること。</p>
     */
    private Bitmap decodeBitmap(Uri uri, int totalRotation){
        try{
            Bitmap bmp;
            try(InputStream is = getContentResolver().openInputStream(uri)){
                if(is == null) return null;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if(bmp == null) return null;

            int degrees = 0;
            if(totalRotation < 0){
                /* クロップ画面を通っていない場合は EXIF を直接読む */
                try(InputStream is2 = getContentResolver().openInputStream(uri)){
                    if(is2 == null){ degrees = 0; }
                    else{
                        android.media.ExifInterface exif = new android.media.ExifInterface(is2);
                        switch(exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                                                    android.media.ExifInterface.ORIENTATION_NORMAL)){
                            case android.media.ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                            case android.media.ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                            case android.media.ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                            default: degrees = 0;
                        }
                    }
                }
            } else {
                degrees = totalRotation;
            }

            if(degrees % 360 == 0) return bmp;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp.recycle();
            return rotated;
        } catch(Exception e){
            return null;
        }
    }
    
    private String saveToDownloads(String cachePath, String fileName) throws Exception{
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
        Uri col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = getContentResolver().insert(col, cv);
        if(itemUri == null) throw new RuntimeException("MediaStore insert 失敗");
        try(FileInputStream in = new FileInputStream(new File(cachePath));
            OutputStream out = getContentResolver().openOutputStream(itemUri)){
            if(out == null) throw new RuntimeException("OutputStream が null");
            byte[] buf = new byte[65536];
            int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        new File(cachePath).delete();
        return fileName;
    }
    
    /* ---------------------------------------------------------------- */
    /* UI 更新                                                            */
    /* ---------------------------------------------------------------- */
    
    private void onSuccess(String fileName){
        mProgress.setVisibility(View.GONE);
        mTvStatus.setText("保存しました: " + fileName);
        setUiEnabled(true);
        Toast.makeText(this, "ダウンロードに保存しました", Toast.LENGTH_LONG).show();
    }
    
    private void onFailure(String msg){
        mProgress.setVisibility(View.GONE);
        mTvStatus.setText("エラー: " + msg);
        setUiEnabled(true);
    }
    
    private void setUiEnabled(boolean enabled){
        mBtnPick.setEnabled(enabled);
        // mBtnPickDir.setEnabled(enabled);
        mBtnCrop.setEnabled(enabled && !mSelectedUris.isEmpty());
        mBtnGenerate.setEnabled(enabled && !mSelectedUris.isEmpty());
    }
    
    private void updateCount(){
        int n = mSelectedUris.size();
        mTvCount.setText(n == 0 ? "画像未選択" : n + " 枚選択中"
                                                 +
                                                 (mCropPoints != null ? " (クロップ設定済)" : ""));
        mBtnCrop.setEnabled(n > 0);
        mBtnGenerate.setEnabled(n > 0);
    }
    
    @Override
    protected void onDestroy(){
        super.onDestroy();
        mExecutor.shutdownNow();
    }
}