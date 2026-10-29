package android.widget;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
public class EditText {
    private final List<TextWatcher> watchers=new ArrayList<TextWatcher>();
    private final Buffer text=new Buffer();
    public Editable getText() { return text; }
    public void addTextChangedListener(TextWatcher watcher) { watchers.add(watcher); }
    public void removeTextChangedListener(TextWatcher watcher) { watchers.remove(watcher); }
    public int watcherCount() { return watchers.size(); }
    private final class Buffer implements Editable {
        private final StringBuilder data=new StringBuilder();
        public Editable replace(int start, int end, CharSequence replacement) {
            for (TextWatcher w : new ArrayList<TextWatcher>(watchers)) w.beforeTextChanged(this,start,end-start,replacement.length());
            data.replace(start,end,replacement.toString());
            for (TextWatcher w : new ArrayList<TextWatcher>(watchers)) w.onTextChanged(this,start,end-start,replacement.length());
            for (TextWatcher w : new ArrayList<TextWatcher>(watchers)) w.afterTextChanged(this);
            return this;
        }
        public int length() { return data.length(); }
        public char charAt(int index) { return data.charAt(index); }
        public CharSequence subSequence(int start, int end) { return data.subSequence(start,end); }
        public String toString() { return data.toString(); }
    }
}
