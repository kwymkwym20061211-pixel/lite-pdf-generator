package light.pdf.generator.kulupu;


import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;


/**
 * DirectorySortDialog
 * <p>
 * ディレクトリ選択前にソート設定を決めるダイアログ。
 * OK を押すと onSortConfigSelected() コールバックを呼ぶ。
 */
public class DirectorySortDialog extends DialogFragment{
    
    public interface OnSortConfigSelected{
        void onSelected(ImageSorter.SortConfig config);
    }
    
    
    private OnSortConfigSelected mListener;
    
    public static DirectorySortDialog newInstance(OnSortConfigSelected listener){
        DirectorySortDialog d = new DirectorySortDialog();
        d.mListener = listener;
        return d;
    }
    
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState){
        View v = LayoutInflater.from(getActivity())
                .inflate(R.layout.dialog_sort, null);
        
        RadioGroup rgKey = v.findViewById(R.id.rg_sort_key);
        RadioGroup rgOrder = v.findViewById(R.id.rg_sort_order);
        
        return new AlertDialog.Builder(getActivity())
                .setTitle("ソート設定")
                .setView(v)
                .setPositiveButton("OK", (dialog, which) -> {
                    if(mListener == null) return;
                    
                    ImageSorter.SortConfig cfg = new ImageSorter.SortConfig();
                    
                    int keyId = rgKey.getCheckedRadioButtonId();
                    if(keyId == R.id.rb_key_date) cfg.key = ImageSorter.SortKey.DATE;
                    else if(keyId == R.id.rb_key_number) cfg.key = ImageSorter.SortKey.NUMBER;
                    else cfg.key = ImageSorter.SortKey.NAME;
                    
                    int ordId = rgOrder.getCheckedRadioButtonId();
                    cfg.order = (ordId == R.id.rb_order_desc)
                            ? ImageSorter.SortOrder.DESC
                            : ImageSorter.SortOrder.ASC;
                    
                    mListener.onSelected(cfg);
                })
                .setNegativeButton("キャンセル", null)
                .create();
    }
}