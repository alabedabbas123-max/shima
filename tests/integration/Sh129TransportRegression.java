import almlk.AnalyzerProtocol;
import com.almlkid.analyzer.AnalyzerCarrier;
import com.almlkid.analyzer.AnalyzerConnection;
import com.almlkid.analyzer.AnalyzerService;
import com.almlkid.kay.ui.CodeLanguageTools;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real app channel code against deterministic Android/Messenger doubles, not device Binder. */
public final class Sh129TransportRegression {
  private static int checks;
  private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
  private static void waitFor(BooleanSupplier done,String message) throws Exception {
    long end=System.nanoTime()+8_000_000_000L;
    while (!done.getAsBoolean() && System.nanoTime()<end) Looper.pump(10);
    check(done.getAsBoolean(),message);
  }
  private static Message request(int what,String wire,Messenger from) {
    Message msg=AnalyzerCarrier.obtain(what,wire); msg.replyTo=from; return msg;
  }
  private static final class Client extends Handler {
    final List<Message> messages=new ArrayList<>(); final Messenger reply=new Messenger(this);
    Client() { super(Looper.getMainLooper()); }
    @Override public void handleMessage(Message msg) { messages.add(msg); }
    int results() { int n=0; for (Message m:messages) if (m.what==AnalyzerProtocol.CODE_RESULT) n++; return n; }
    boolean state(String state) {
      for (Message m:messages) if (m.what==AnalyzerProtocol.CODE_STATE
          && state.equals(AnalyzerProtocol.stateFields(AnalyzerCarrier.payload(m))[0])) return true;
      return false;
    }
  }
  private static void sendHead(Messenger server,Client client,String file,long gen,String text) throws Exception {
    String wire=AnalyzerProtocol.encodeRequest(new AnalyzerProtocol.Request(gen,file,text.length(),1));
    server.send(request(AnalyzerProtocol.CODE_BEGIN,wire,client.reply));
  }
  private static void sendPart(Messenger server,Client client,String file,long gen,int index,String text) throws Exception {
    server.send(request(AnalyzerProtocol.CODE_PART,AnalyzerProtocol.encodePart(
        new AnalyzerProtocol.Part(gen,index,file,text)),client.reply));
  }
  private static void sendEnd(Messenger server,Client client,String file,long gen,String text) throws Exception {
    String wire=AnalyzerProtocol.encodeRequest(new AnalyzerProtocol.Request(gen,file,text.length(),1));
    server.send(request(AnalyzerProtocol.CODE_END,wire,client.reply));
  }
  private static void serviceIsolation() throws Exception {
    AnalyzerService service=new AnalyzerService(); Messenger server=new Messenger(service.onBind(null));
    Client a=new Client(), b=new Client(); String file="Same.java";
    String one="class One {}", two="class Two {}";
    sendHead(server,a,file,7,one); sendHead(server,b,file,7,two); Looper.pump(30);
    check(service.sessionCount()==2,"same filename and generation use separate client sessions");
    sendPart(server,a,file,7,0,one); sendPart(server,b,file,7,0,two);
    sendEnd(server,b,file,7,two); sendEnd(server,a,file,7,one);
    waitFor(() -> a.results()==1 && b.results()==1,"both isolated sessions receive results");
    AnalyzerProtocol.Reply ra=AnalyzerProtocol.decode(AnalyzerCarrier.payload(a.messages.get(a.messages.size()-1)));
    AnalyzerProtocol.Reply rb=AnalyzerProtocol.decode(AnalyzerCarrier.payload(b.messages.get(b.messages.size()-1)));
    check(ra!=null && rb!=null && ra.generation==7 && rb.generation==7,"generation survives both replies");

    sendHead(server,a,file,8,one); Looper.pump(20);
    sendEnd(server,a,file,999,one); Looper.pump(20);
    check(service.sessionCount()==1,"wrong-generation end cannot remove the current session");
    sendPart(server,a,file,8,0,one); sendEnd(server,a,file,8,one);
    waitFor(() -> a.results()==2,"current session succeeds after a stale end");

    sendHead(server,a,file,9,one); sendPart(server,a,file,9,1,one); Looper.pump(30);
    check(service.sessionCount()==0 && a.state(AnalyzerProtocol.STATE_REFUSED),"out-of-order part is refused, not analyzed");
    Message state=a.messages.get(a.messages.size()-1);
    String[] fields=AnalyzerProtocol.stateFields(AnalyzerCarrier.payload(state));
    check(fields.length==4 && "9".equals(fields[2]) && file.equals(fields[3]),"refusal is correlated with its exact request");
    service.onDestroy();
  }
  private static final class FakeContext extends Context {
    final List<ServiceConnection> callbacks=new ArrayList<>(); final IBinder server;
    FakeContext(IBinder server) { this.server=server; }
    @Override public boolean bindService(Intent i,ServiceConnection c,int flags) { callbacks.add(c); return true; }
    @Override public void unbindService(ServiceConnection c) { }
    void connect(int index) { callbacks.get(index).onServiceConnected(new ComponentName(),server); }
  }
  private static final class Server extends Handler {
    final List<AnalyzerProtocol.Part> parts=new ArrayList<>();
    AnalyzerProtocol.Request head; Messenger reply;
    Server() { super(Looper.getMainLooper()); }
    @Override public void handleMessage(Message msg) {
      if (msg.what==AnalyzerProtocol.CODE_BEGIN) { head=AnalyzerProtocol.decodeRequest(AnalyzerCarrier.payload(msg)); reply=msg.replyTo; }
      else if (msg.what==AnalyzerProtocol.CODE_PART) parts.add(AnalyzerProtocol.decodePart(AnalyzerCarrier.payload(msg)));
    }
    void result(long generation,String file) throws Exception {
      reply.send(AnalyzerCarrier.obtain(AnalyzerProtocol.CODE_RESULT,
          AnalyzerProtocol.encode(new AnalyzerProtocol.Builder(generation,file).build())));
    }
  }
  private static void connectionBehavior() throws Exception {
    Server server=new Server(); FakeContext context=new FakeContext(new Messenger(server).getBinder());
    final int[] applied={0};
    AnalyzerConnection connection=new AnalyzerConnection(new AnalyzerConnection.Sink() {
      public void applied(String file,List<CodeLanguageTools.Diagnostic> diagnostics,
          AnalyzerProtocol.Members members,AnalyzerProtocol.Reply reply) { applied[0]++; }
    });
    connection.attach(context);
    check(context.callbacks.size()==1,"first connection binds");
    connection.detach(); connection.attach(context);
    check(context.callbacks.size()==2,"reattach starts a new binding epoch");
    context.connect(0); Looper.pump(20);
    connection.request("Big.java","abc"); Looper.pump(20);
    check(server.head==null,"late callback from detached binding is ignored");
    context.connect(1);
    waitFor(() -> server.head!=null,"new binding drains the latest request");
    waitFor(() -> !server.parts.isEmpty(),"transport sends the first part");
    server.result(server.head.generation+99,server.head.file); Looper.pump(30);
    check(connection.isBusy() && applied[0]==0,"unrelated stale response cannot finish current request");
    server.reply.send(AnalyzerCarrier.obtain(AnalyzerProtocol.CODE_STATE,
        AnalyzerProtocol.encodeRequestState(AnalyzerProtocol.STATE_REFUSED,"old",server.head.generation+99,server.head.file)));
    Looper.pump(30);
    check(connection.isBusy(),"stale refusal cannot finish a different in-flight request");
    server.result(server.head.generation,server.head.file);
    waitFor(() -> applied[0]==1,"matching response is applied once");
    check(!connection.isBusy(),"matching reply releases in-flight slot");

    server.parts.clear(); server.head=null;
    String large=new String(new char[47_999]).replace('\0','x') + "😀"
        + new String(new char[107_000]).replace('\0','x');
    AtomicReference<Thread> sending=new AtomicReference<>();
    Thread mainThread=Thread.currentThread();
    Messenger.observer=(msg,thread) -> { if (msg.what==AnalyzerProtocol.CODE_BEGIN) sending.set(thread); };
    connection.request("Big.java",large);
    waitFor(() -> server.head!=null && server.parts.size()==AnalyzerProtocol.partCount(large.length()),"large file is fully split and sent");
    Messenger.observer=null;
    check(sending.get()!=null && sending.get()!=mainThread,"chunk preparation and Binder sends run off the UI thread");
    StringBuilder joined=new StringBuilder(); int expectedIndex=0;
    for (AnalyzerProtocol.Part p:server.parts) {
      check(p.index==expectedIndex++ && p.text.length()<=48_000
          && (p.text.isEmpty() || !Character.isLowSurrogate(p.text.charAt(0))),
          "chunks are ordered, bounded and do not split a surrogate pair");
      joined.append(p.text);
    }
    check(joined.toString().equals(large),"no part of a large snapshot is dropped");
    server.result(server.head.generation,server.head.file); waitFor(() -> applied[0]==2,"large-file reply applies");
    connection.detach();
  }
  public static void main(String[] args) throws Exception {
    serviceIsolation(); connectionBehavior();
    System.out.println("PASS: " + checks + " sh129 transport checks (Android/Messenger host doubles; not on-device Binder)");
  }
}
