package android.os;
public final class Message {
    public int what; public Object obj; public Messenger replyTo; private Bundle data;
    public static Message obtain(Handler ignored,int what) { Message msg=new Message(); msg.what=what; return msg; }
    public void setData(Bundle data) { this.data=data; }
    public Bundle getData() { return data; }
}
