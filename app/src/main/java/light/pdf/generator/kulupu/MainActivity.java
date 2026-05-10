package light.pdf.generator.kulupu;


import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
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
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends Activity{
    
    /* リクエストコード */
    private static final int REQ_PICK_IMAGES = 1;
    
    /* 選択済み画像 URI リスト */
    private final List<Uri> mSelectedUris = new ArrayList<>();
    
    /* 設定値 */
    private int mDstChannels = PdfBuilder.CHANNELS_GRAY;
    private int mBitsPerCh = PdfBuilder.BPC_4;
    
    /* UI */
    private TextView mTvCount;
    private Button mBtnPick;
    private Button mBtnGenerate;
    private ProgressBar mProgress;
    private TextView mTvStatus;
    
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    
    /* ---------------------------------------------------------------- */
    
    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mTvCount = findViewById(R.id.tv_count);
        mBtnPick = findViewById(R.id.btn_pick);
        mBtnGenerate = findViewById(R.id.btn_generate);
        mProgress = findViewById(R.id.progress);
        mTvStatus = findViewById(R.id.tv_status);
        
        /* --- 画像選択ボタン --- */
        mBtnPick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(intent, "画像を選択"), REQ_PICK_IMAGES);
        });
        
        /* --- モード Spinner --- */
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
        
        /* --- ビット深度 Spinner --- */
        Spinner spinnerBpc = findViewById(R.id.spinner_bpc);
        ArrayAdapter<String> bpcAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"1 bit", "2 bit", "4 bit", "8 bit"});
        bpcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBpc.setAdapter(bpcAdapter);
        spinnerBpc.setSelection(2); /* デフォルト 4bit */
        spinnerBpc.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            private final int[] BPC = {1, 2, 4, 8};
            
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id){
                mBitsPerCh = BPC[pos];
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> p){}
        });
        
        /* --- 生成ボタン --- */
        mBtnGenerate.setOnClickListener(v -> startGenerate());
        
        updateCount();
    }
    
    /* ---------------------------------------------------------------- */
    /* 画像選択結果                                                       */
    /* ---------------------------------------------------------------- */
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode != REQ_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;
        
        mSelectedUris.clear();
        
        if(data.getClipData() != null){
            /* 複数選択 */
            int count = data.getClipData().getItemCount();
            for(int i = 0; i < count; i++)
                mSelectedUris.add(data.getClipData().getItemAt(i).getUri());
        } else if(data.getData() != null){
            /* 単一選択 */
            mSelectedUris.add(data.getData());
        }
        
        updateCount();
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
        
        /* コピーしてバックグラウンドに渡す */
        List<Uri> uris = new ArrayList<>(mSelectedUris);
        int dstCh = mDstChannels;
        int bpc = mBitsPerCh;
        String fileName = "board_"
                          + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                          + ".pdf";
        
        mExecutor.execute(() -> generate(uris, dstCh, bpc, fileName));
    }
    
    private void generate(List<Uri> uris, int dstCh, int bpc, String fileName){
        PdfBuilder builder = new PdfBuilder(this);
        try{
            builder.start(fileName);
            
            for(int i = 0; i < uris.size(); i++){
                Uri uri = uris.get(i);
                
                /* URI → Bitmap (必要最小限のサンプリング) */
                Bitmap bmp = decodeBitmap(uri);
                if(bmp == null) throw new RuntimeException("画像の読み込み失敗: " + uri);
                
                builder.appendImage(bmp, dstCh, bpc);
                bmp.recycle();
                
                final int progress = i + 1;
                mMain.post(() -> mProgress.setProgress(progress));
            }
            
            String cachePath = builder.end();
            
            /* Downloads に保存 */
            String savedPath = saveToDownloads(cachePath, fileName);
            
            mMain.post(() -> onSuccess(savedPath));
            
        } catch(Exception e){
            builder.abort();
            mMain.post(() -> onFailure(e.getMessage()));
        }
    }
    
    /* URI → Bitmap。ContentResolver 経由。 */
    private Bitmap decodeBitmap(Uri uri){
        try{
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(uri), null, opts);
        } catch(Exception e){
            return null;
        }
    }
    
    /* キャッシュファイル → MediaStore Downloads に保存し、パスを返す */
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
        
        /* キャッシュ削除 */
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
        mBtnGenerate.setEnabled(enabled);
    }
    
    private void updateCount(){
        int n = mSelectedUris.size();
        mTvCount.setText(n == 0 ? "画像未選択" : n + " 枚選択中");
        mBtnGenerate.setEnabled(n > 0);
    }
    
    @Override
    protected void onDestroy(){
        super.onDestroy();
        mExecutor.shutdownNow();
    }
}