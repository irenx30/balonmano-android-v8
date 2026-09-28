
package com.balonmano.app;

import android.app.*;
import android.os.Bundle;
import android.os.Handler;
import android.content.*;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.*;
import android.util.Patterns;
import android.view.*;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    interface StrCallback { void run(String s); }

    static class Row implements Comparable<Row> {
        String[] cells; double sortKey;
        Row(String[] c, double k){cells=c;sortKey=k;}
        public int compareTo(Row o){ return Double.compare(o.sortKey, sortKey); }
    }

    DB db;
    LinearLayout root, content;
    TextView score, minuteLabel;
    int currentMatch = -1;
    Integer selectedPlayer = null;
    String pendingAction = null;
    String pendingZone = null;
    String currentScreen = "home";

    // ---- Cronómetro del partido ----
    long timerAccumulatedMillis = 0;
    long timerStartMillis = -1;
    boolean timerRunning = false;
    Handler tickHandler = new Handler();
    boolean tickerStarted = false;

    String pendingFinalAction, pendingFinalZone, pendingFinalResult;

    // Listas EXACTAMENTE iguales a las de la app de Python (app_pyhton.py).
    // "7 metros" está incluida como una zona más de lanzamiento (ya no es una acción aparte).
    static final String[] ZONAS = {
        "Extremo izquierdo", "6 metros", "Lateral izquierdo", "Central",
        "Lateral derecho", "Extremo derecho", "7 metros"
    };
    static final String[] DIRECCIONES = {"Arriba", "Centro", "Abajo", "Izquierda", "Derecha"};

    // Posiciones disponibles para el desplegable de "Añadir jugador".
    static final String[] POSICIONES = {
        "Portero", "Extremo izquierdo", "Extremo derecho",
        "Lateral izquierdo", "Lateral derecho", "Central", "Pivote"
    };

    // Tipos de exclusión disponibles al registrar una "Exclusión".
    static final String[] TIPOS_EXCLUSION = {
        "Tarjeta amarilla", "Tarjeta azul", "Tarjeta roja", "2 minutos", "Expulsión"
    };
    // Tipos de exclusión que cuentan como "tarjeta" para las estadísticas.
    static final Set<String> TARJETAS = new HashSet<>(Arrays.asList("Tarjeta amarilla","Tarjeta azul","Tarjeta roja"));

    static final String[] GOAL_CELLS = {
        "Escuadra izquierda", "Arriba centro", "Escuadra derecha",
        "Medio izquierda", "Centro portería", "Medio derecha",
        "Abajo izquierda", "Abajo centro", "Abajo derecha"
    };
    static final String[] GOAL_ICONS = {"↖","↑","↗","←","•","→","↙","↓","↘"};

    // -------- Paleta de colores (tema oscuro "azul noche + dorado", estilo de referencia) --------
    static final int COLOR_PRIMARY = 0xff1c4a72;
    static final int COLOR_PRIMARY_DARK = 0xff081521;
    static final int COLOR_ACCENT = 0xfff5a623;
    static final int COLOR_ACCENT_TEXT = 0xff3a2400;
    static final int COLOR_BG = 0xff0a1f33;
    static final int COLOR_TEXT = 0xfff2f6fa;
    static final int COLOR_MUTED = 0xff9fb3c8;
    static final int COLOR_GREEN = 0xff28a866;
    static final int COLOR_RED = 0xffe0524a;
    static final int COLOR_TEAL = 0xff3a92c2;
    static final int COLOR_GRAY = 0xff7c8ea3;
    static final int COLOR_AMBER = 0xffdd8a1e;
    static final int COLOR_CARD = 0xff11304f;
    static final int COLOR_CARD_ALT = 0xff0d2740;
    static final int COLOR_BORDER = 0x33f5a623;
    static final int COLOR_SURFACE_DARK = 0xff0d3b23;
    static final int COLOR_SURFACE_DARK2 = 0xff0a2438;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        db=new DB(this);
        auth=FirebaseAuth.getInstance();
        firestore=FirebaseFirestore.getInstance();
        if(auth.getCurrentUser()==null) login(); else home();
    }

    // ============================================================
    // CUENTA / SESIÓN (Firebase Authentication + Cloud Firestore)
    // ============================================================
    FirebaseAuth auth;
    FirebaseFirestore firestore;

    // Nombres de las tablas que se incluyen en la copia de seguridad en la nube.
    // (las demás tablas del esquema no se usan actualmente desde la app)
    static final String[] BACKUP_TABLES = {"jugadores","partidos","acciones","porteros","lanzamientos_porteria"};

    void login(){
        base("🔐 Iniciar sesión", null);
        add(plain("Inicia sesión para guardar y sincronizar tus partidos y jugadores en la nube.",14,COLOR_MUTED),16);
        LinearLayout c=card();
        EditText email=new EditText(this); email.setHint("Correo electrónico"); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); c.addView(email);
        EditText pass=new EditText(this); pass.setHint("Contraseña (mínimo 6 caracteres)"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); c.addView(pass);
        add(c);

        Button loginBtn=btn("➡️ Iniciar sesión");
        loginBtn.setOnClickListener(v->{
            String e=email.getText().toString().trim(), p=pass.getText().toString();
            if(!validEmailPass(e,p)) return;
            auth.signInWithEmailAndPassword(e,p)
                .addOnSuccessListener(r->{ toast("Sesión iniciada"); afterLogin(); })
                .addOnFailureListener(err->toast("No se pudo iniciar sesión: "+friendlyAuthError(err)));
        });
        add(loginBtn);

        Button registerBtn=btnGhost("✨ Crear cuenta nueva");
        registerBtn.setOnClickListener(v->{
            String e=email.getText().toString().trim(), p=pass.getText().toString();
            if(!validEmailPass(e,p)) return;
            auth.createUserWithEmailAndPassword(e,p)
                .addOnSuccessListener(r->{ toast("Cuenta creada"); afterLogin(); })
                .addOnFailureListener(err->toast("No se pudo crear la cuenta: "+friendlyAuthError(err)));
        });
        add(registerBtn);

        Button forgot=btnGhost("¿Has olvidado tu contraseña?");
        forgot.setOnClickListener(v->{
            String e=email.getText().toString().trim();
            if(e.isEmpty()||!Patterns.EMAIL_ADDRESS.matcher(e).matches()){ toast("Escribe primero tu correo arriba"); return; }
            auth.sendPasswordResetEmail(e)
                .addOnSuccessListener(r->toast("Te hemos enviado un correo para restablecerla"))
                .addOnFailureListener(err->toast("No se pudo enviar el correo: "+friendlyAuthError(err)));
        });
        add(forgot);
    }
    boolean validEmailPass(String e,String p){
        if(e.isEmpty()||!Patterns.EMAIL_ADDRESS.matcher(e).matches()){ toast("Escribe un correo válido"); return false; }
        if(p.length()<6){ toast("La contraseña debe tener al menos 6 caracteres"); return false; }
        return true;
    }
    String friendlyAuthError(Exception e){
        String m=e.getMessage();
        return m==null?"error desconocido":m;
    }
    void afterLogin(){
        // Al iniciar sesión, si en la nube hay una copia de seguridad y el
        // dispositivo está vacío (instalación nueva), la restauramos automáticamente.
        boolean localVacio = db.isEmpty(BACKUP_TABLES);
        if(localVacio){
            restoreFromCloud(true);
        } else {
            home();
        }
    }
    void logout(){
        auth.signOut();
        login();
    }

    // ---- Pantalla de cuenta: mostrar email, sincronizar y cerrar sesión ----
    void account(){
        base("👤 Mi cuenta","account");
        FirebaseUser u=auth.getCurrentUser();
        LinearLayout c=card();
        TextView t=plain(u!=null?u.getEmail():"", 16, COLOR_TEXT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        c.addView(t); add(c);

        Button backup=btn("☁️ Guardar copia en la nube");
        backup.setOnClickListener(v->backupToCloud());
        add(backup);

        Button restore=btnGhost("☁️ Restaurar última copia de la nube");
        restore.setOnClickListener(v->{
            new AlertDialog.Builder(this)
                .setTitle("Restaurar copia")
                .setMessage("Esto reemplazará los datos que tienes ahora mismo en este dispositivo por la última copia guardada en la nube. ¿Continuar?")
                .setPositiveButton("Restaurar",(dlg,w)->restoreFromCloud(false))
                .setNegativeButton("Cancelar",null)
                .show();
        });
        add(restore);

        Button logoutBtn=btnDanger("🚪 Cerrar sesión");
        logoutBtn.setOnClickListener(v->{
            new AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("¿Seguro que quieres cerrar sesión?")
                .setPositiveButton("Cerrar sesión",(dlg,w)->logout())
                .setNegativeButton("Cancelar",null)
                .show();
        });
        add(logoutBtn);

        add(plain("La copia en la nube guarda tus jugadores, partidos y acciones registradas. Recuerda pulsar \"Guardar copia\" después de anotar un partido si quieres tenerlo también en otro dispositivo.",12,COLOR_MUTED),16);
    }

    void backupToCloud(){
        FirebaseUser u=auth.getCurrentUser();
        if(u==null){ login(); return; }
        try{
            JSONObject payload=new JSONObject();
            for(String table:BACKUP_TABLES) payload.put(table, db.tableToJson(table));
            payload.put("actualizado", System.currentTimeMillis());

            Map<String,Object> doc=new HashMap<>();
            doc.put("data", payload.toString());
            doc.put("actualizado", System.currentTimeMillis());

            firestore.collection("users").document(u.getUid()).collection("backup").document("data").set(doc)
                .addOnSuccessListener(r->toast("Copia guardada en la nube"))
                .addOnFailureListener(err->toast("No se pudo guardar la copia: "+friendlyAuthError(err)));
        }catch(Exception e){ toast("No se pudo preparar la copia de seguridad"); }
    }

    void restoreFromCloud(boolean silentIfMissing){
        FirebaseUser u=auth.getCurrentUser();
        if(u==null){ login(); return; }
        firestore.collection("users").document(u.getUid()).collection("backup").document("data").get()
            .addOnSuccessListener(this::onRestoreLoaded)
            .addOnFailureListener(err->{
                if(!silentIfMissing) toast("No se pudo restaurar: "+friendlyAuthError(err));
                home();
            });
        if(silentIfMissing){
            // Si no hay copia en la nube todavía (cuenta nueva), simplemente vamos a la pantalla principal.
        }
    }
    void onRestoreLoaded(DocumentSnapshot snap){
        try{
            if(!snap.exists()){ home(); return; }
            String raw=(String)snap.get("data");
            if(raw==null){ home(); return; }
            JSONObject payload=new JSONObject(raw);
            db.wipeTables(BACKUP_TABLES);
            for(String table:BACKUP_TABLES){
                if(payload.has(table)) db.jsonToTable(table, payload.getJSONArray(table));
            }
            toast("Datos restaurados desde la nube");
        }catch(Exception e){
            toast("No se pudo restaurar la copia de la nube");
        } finally {
            home();
        }
    }

    // ============================================================
    // EXPORTAR ESTADÍSTICAS A PDF
    // ============================================================
    static final int REQUEST_CREATE_PDF = 1001;
    byte[] pendingPdfBytes = null;

    void exportContentToPdf(String fileBaseName){
        try{
            if(content==null || content.getWidth()==0 || content.getHeight()==0){
                toast("No hay contenido para exportar todavía");
                return;
            }
            Bitmap bmp=Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(bmp);
            c.drawColor(0xffffffff);
            content.draw(c);

            PdfDocument doc=new PdfDocument();
            PdfDocument.PageInfo pageInfo=new PdfDocument.PageInfo.Builder(bmp.getWidth(), bmp.getHeight(), 1).create();
            PdfDocument.Page page=doc.startPage(pageInfo);
            page.getCanvas().drawBitmap(bmp,0,0,null);
            doc.finishPage(page);

            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            doc.writeTo(bos);
            doc.close();
            pendingPdfBytes=bos.toByteArray();

            String safeName=fileBaseName.replaceAll("[^A-Za-zÁÉÍÓÚÜÑáéíóúüñ0-9 _-]","").trim();
            if(safeName.isEmpty()) safeName="estadisticas";

            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_TITLE, safeName+".pdf");
            startActivityForResult(intent, REQUEST_CREATE_PDF);
        }catch(Exception e){
            toast("No se pudo generar el PDF");
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_CREATE_PDF && resultCode==RESULT_OK && data!=null && pendingPdfBytes!=null){
            Uri uri=data.getData();
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                out.write(pendingPdfBytes);
                toast("PDF guardado correctamente");
            }catch(Exception e){
                toast("No se pudo guardar el PDF");
            } finally {
                pendingPdfBytes=null;
            }
        }
    }

    // ============================================================
    // ESTILO / HELPERS VISUALES
    // ============================================================
    float density(){ return getResources().getDisplayMetrics().density; }

    GradientDrawable rounded(int color,float radiusDp){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color); g.setCornerRadius(radiusDp*density());
        return g;
    }
    GradientDrawable roundedStroke(int color,float radiusDp){
        GradientDrawable g=new GradientDrawable();
        g.setColor(0x00000000); g.setStroke((int)(2*density()),color);
        g.setCornerRadius(radiusDp*density());
        return g;
    }
    Drawable ripple(GradientDrawable base,int rippleColor,float radiusDp){
        GradientDrawable mask=rounded(0xffffffff,radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask);
    }

    TextView plain(String s,int size,int color){
        TextView t=new TextView(this); t.setText(s==null?"":s); t.setTextSize(size); t.setTextColor(color);
        return t;
    }
    TextView tv(String s,int size){
        TextView t=plain(s,size,COLOR_TEXT); t.setPadding(0,8,0,8);
        return t;
    }
    Button btnColor(String s,int bg,int textColor){
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(textColor);
        b.setSingleLine(false); b.setMaxLines(2); b.setEllipsize(null);
        b.setBackground(ripple(rounded(bg,18),0x33ffffff,18));
        b.setPadding(24,28,24,28); b.setMinHeight(0); b.setElevation(3);
        return b;
    }
    Button btn(String s){ return btnColor(s,COLOR_PRIMARY,0xffffffff); }
    // Botón "fantasma": relleno oscuro con borde dorado y texto blanco (estilo "Ver visión global").
    Button btnGhost(String s){
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(0xffffffff);
        b.setSingleLine(false); b.setMaxLines(2); b.setEllipsize(null);
        GradientDrawable base=rounded(COLOR_CARD,18); base.setStroke((int)(1.6f*density()),COLOR_ACCENT);
        b.setBackground(ripple(base,0x33f5a623,18));
        b.setPadding(20,22,20,22); b.setMinHeight(0);
        return b;
    }
    Button btnDanger(String s){ return btnColor(s,COLOR_RED,0xffffffff); }

    void add(View v){ add(v,14); }
    void add(View v,int marginBottomPx){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=marginBottomPx;
        content.addView(v,lp);
    }
    void gap(){ Space s=new Space(this); content.addView(s,new LinearLayout.LayoutParams(1,10)); }
    void addChip(LinearLayout row,View chip){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.gravity=Gravity.CENTER;
        row.addView(chip,lp);
    }

    LinearLayout card(){
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg=rounded(COLOR_CARD,22);
        bg.setStroke((int)(1.2f*density()),COLOR_BORDER);
        l.setBackground(bg); l.setPadding(26,24,26,24); l.setElevation(4);
        return l;
    }
    void section(String title,View body){
        LinearLayout c=card();
        TextView t=plain(title,16,COLOR_ACCENT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,0,0,16);
        c.addView(t); c.addView(body);
        add(c);
    }
    // ---- Fila de navegación estilo "lista con icono circular + chevron" (pantalla de inicio) ----
    View navRow(String icon,String title,int accentBar,Runnable onClick){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg=rounded(COLOR_CARD,20); bg.setStroke((int)(1.2f*density()),COLOR_BORDER);
        row.setBackground(ripple(bg,0x22f5a623,20));
        row.setPadding(22,26,22,26); row.setElevation(3);
        if(accentBar!=0){
            View bar=new View(this); bar.setBackground(rounded(accentBar,4));
            LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams((int)(5*density()),(int)(34*density())); blp.rightMargin=18;
            row.addView(bar,blp);
        }
        FrameLayout ic=new FrameLayout(this); GradientDrawable icbg=roundedStroke(COLOR_ACCENT,24); ic.setBackground(icbg);
        TextView icTv=plain(icon,18,COLOR_ACCENT); icTv.setGravity(Gravity.CENTER);
        ic.addView(icTv,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams icRowLp=new LinearLayout.LayoutParams((int)(46*density()),(int)(46*density())); icRowLp.rightMargin=18;
        row.addView(ic,icRowLp);
        TextView t=plain(title,17,0xffffffff); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView chev=plain("›",22,COLOR_MUTED); chev.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        row.addView(chev,new LinearLayout.LayoutParams(-2,-2));
        row.setOnClickListener(v->onClick.run());
        return row;
    }
    void sectionTable(String title,String[] headers,List<String[]> rows){
        HorizontalScrollView sv=new HorizontalScrollView(this);
        sv.addView(buildTable(headers,rows));
        section(title,sv);
    }
    void sectionText(String title,String text){
        section(title,plain(text,14,COLOR_MUTED));
    }
    // Título grande de agrupación (no es una tarjeta), para separar visualmente
    // bloques de estadísticas distintos (p.ej. "Jugadores" vs "Porteros").
    void groupHeader(String title){
        TextView t=plain(title,18,COLOR_ACCENT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(4,18,4,6);
        add(t,10);
    }
    // Botón para exportar la pantalla de estadísticas actual a PDF.
    void exportPdfButton(String fileBaseName){
        Button b=btnGhost("📄 Descargar como PDF");
        b.setOnClickListener(v->exportContentToPdf(fileBaseName));
        add(b);
    }
    View statChip(String label,String value){
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
        TextView v=plain(value,19,0xffffffff); v.setTypeface(Typeface.DEFAULT,Typeface.BOLD); v.setGravity(Gravity.CENTER);
        TextView lb=plain(label,11,COLOR_MUTED); lb.setGravity(Gravity.CENTER); lb.setPadding(0,2,0,0);
        l.addView(v); l.addView(lb);
        return l;
    }

    // ---- Tablas ----
    TextView tableCell(String s, boolean header, boolean firstCol){
        TextView t=new TextView(this); t.setText(s==null?"":s);
        t.setPadding(22,16,22,16); t.setTextSize(13); t.setMinWidth(firstCol?150:96);
        if(header){ t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setTextColor(0xffffffff); }
        else if(firstCol){ t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setTextColor(COLOR_TEXT); }
        else { t.setTextColor(COLOR_TEXT); }
        return t;
    }
    TableLayout buildTable(String[] headers, List<String[]> rows){
        TableLayout t=new TableLayout(this);
        TableRow hr=new TableRow(this); hr.setBackground(rounded(COLOR_PRIMARY,10));
        for(int i=0;i<headers.length;i++) hr.addView(tableCell(headers[i],true,false));
        t.addView(hr);
        int i=0;
        for(String[] row:rows){
            TableRow r=new TableRow(this); r.setBackgroundColor(i%2==0?COLOR_CARD:COLOR_CARD_ALT);
            for(int c=0;c<row.length;c++) r.addView(tableCell(row[c],false,c==0));
            t.addView(r); i++;
        }
        return t;
    }
    void addTable(String[] headers, List<String[]> rows){
        HorizontalScrollView sv=new HorizontalScrollView(this);
        sv.addView(buildTable(headers,rows));
        add(sv);
    }
    String pct(int part,int total){
        double p = total>0 ? (100.0*part/total) : 0;
        return String.format(Locale.getDefault(),"%.1f%%",p);
    }

    // ---- Fila de estadísticas de una jugadora: goles, intentos, % éxito, asistencias, pérdidas,
    // recuperaciones, exclusiones y tarjetas. (Ya no se muestran 1x1, "% 7 metros" ni "% Lanz. de juego").
    List<String[]> statRows(int golesDirectos,int lanzTotal,int lanzGol,int asist,int perd,int recup,
                             int m7g,int m7lAttempt,int exclus,int tarjetas){
        int golesLanz = golesDirectos+lanzGol;
        int lanzNormalTotal = golesDirectos+lanzTotal;
        int m7Total = m7g+m7lAttempt;
        int totalGoles = golesLanz + m7g;
        int totalIntentos = lanzNormalTotal + m7Total;
        List<String[]> rows=new ArrayList<>();
        rows.add(new String[]{"⚽ Goles", ""+totalGoles});
        rows.add(new String[]{"🎯 Lanzamientos", ""+totalIntentos});
        rows.add(new String[]{"✅ % Éxito", pct(totalGoles,totalIntentos)});
        rows.add(new String[]{"🤝 Asistencias", ""+asist});
        rows.add(new String[]{"❌ Pérdidas", ""+perd});
        rows.add(new String[]{"🔄 Recuperaciones", ""+recup});
        rows.add(new String[]{"🟥 Exclusiones", ""+exclus});
        rows.add(new String[]{"🟨 Tarjetas", ""+tarjetas});
        return rows;
    }

    // ---- Rejilla de portería 3x3 (para marcar dónde ha entrado un gol) ----
    View goalGridWidget(StrCallback cb){
        // Marco exterior estilo "poste de portería" (franjas rojas y blancas), como en la foto de referencia.
        FrameLayout outer=new FrameLayout(this);
        outer.setBackground(rounded(COLOR_PRIMARY,22));
        int framePad=(int)(8*density());
        outer.setPadding(framePad,framePad,framePad,framePad);

        LinearLayout grid=new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(rounded(COLOR_SURFACE_DARK,16)); grid.setPadding(16,16,16,16);
        int idx=0;
        for(int r=0;r<3;r++){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for(int cIdx=0;cIdx<3;cIdx++){
                final String cellLabel=GOAL_CELLS[idx];
                TextView cell=new TextView(this);
                cell.setText(GOAL_ICONS[idx]); cell.setTextSize(26); cell.setTextColor(0xffffffff); cell.setGravity(Gravity.CENTER);
                cell.setBackground(ripple(rounded(0x26ffffff,12),0x55ffffff,12));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,190); lp.weight=1; lp.setMargins(5,5,5,5);
                cell.setLayoutParams(lp);
                cell.setOnClickListener(v->cb.run(cellLabel));
                row.addView(cell);
                idx++;
            }
            grid.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(grid,new FrameLayout.LayoutParams(-1,-2));
        return outer;
    }

    // ---- Semicírculo de pista para elegir la zona de lanzamiento ----
    // La silueta (línea de 9m discontinua, área de 6m sombreada y portería) se dibuja
    // igual que en un campo de balonmano real, y los botones de zona se colocan encima,
    // cada uno en su posición correspondiente sobre esa silueta.
    String zoneShort(String z){
        switch(z){
            case "Extremo izquierdo": return "Extremo\nizq.";
            case "Lateral izquierdo": return "Lateral\nizq.";
            case "Extremo derecho": return "Extremo\nder.";
            case "Lateral derecho": return "Lateral\nder.";
            case "6 metros": return "6 m";
            case "7 metros": return "7 m";
            default: return z;
        }
    }
    // Tamaño del rectángulo de pista: se adapta al ancho real de la pantalla (más grande
    // en pantallas anchas) sin desbordar, con un máximo razonable.
    int[] courtSizePx(){
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int marginPx = (int)(40*density());
        int wPx = Math.min(screenW-marginPx, (int)(430*density()));
        int hPx = (int)(wPx*0.86f);
        return new int[]{wPx,hPx};
    }
    // Geometría compartida de la pista: centro de la portería (cx,cy), radio de la línea
    // de 9 metros (r9), radio del área de 6 metros (r6) y radio de la marca de 7 metros (r7).
    // Entre r6 y r7 se deja siempre un hueco mínimo fijo para que las zonas "6 metros" y
    // "7 metros" no se solapen aunque la pista sea pequeña.
    // La portería está ARRIBA de la pista (para verla "de cara", como si el usuario
    // estuviera detrás de la portería mirando hacia el campo) y el semicírculo se abre
    // hacia ABAJO. cy queda cerca del borde superior del rectángulo.
    float[] courtGeom(int wPx,int hPx){
        float cx=wPx/2f, cy=hPx*0.08f;
        float r9=Math.min(hPx*0.80f, wPx/2f-10*density());
        float r6=r9*0.54f;
        float minGap=56*density();
        float r7=Math.min(r6+minGap, r6+(r9-r6)*0.82f);
        return new float[]{cx,cy,r9,r6,r7};
    }
    // Punto sobre la pista dado un ángulo (0°=derecha, 90°=abajo, 180°=izquierda) y un radio.
    // (Con la portería arriba, "abajo" es la dirección hacia dentro del campo.)
    int[] courtPoint(float cx,float cy,float r,double angleDeg){
        double rad=Math.toRadians(angleDeg);
        int x=(int)Math.round(cx+r*Math.cos(rad));
        int y=(int)Math.round(cy+r*Math.sin(rad));
        return new int[]{x,y};
    }
    // Ángulo y radio (como fracción de r9/r6/r7) de cada zona de ZONAS sobre la pista.
    // Los extremos van pegados a los postes (extremo del área de 6m), los laterales y el
    // central sobre la línea de 9m, el pivote ("6 metros") en lo alto del área, y "7 metros"
    // en la marca de penalti, entre la portería y el área.
    static final double[] ZONA_ANGULO = {170, 90, 138, 90, 42, 10, 90};
    static final int[]    ZONA_RADIO_TIPO = {1, 2, 0, 0, 0, 1, 3}; // 0=r9  1=r6  2=r6(pivote)  3=r7

    // ---- Dibujo de la silueta de la pista (línea de 9m discontinua + área de 6m) ----
    class CourtView extends View {
        int wPx,hPx;
        CourtView(int wPx,int hPx){ super(MainActivity.this); this.wPx=wPx; this.hPx=hPx; setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float[] g=courtGeom(wPx,hPx);
            float cx=g[0], cy=g[1], r9=g[2], r6=g[3];
            float rad24=24*density();

            Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(0xfff2f5f8); bg.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(0,0,wPx,hPx), rad24, rad24, bg);

            Paint border=new Paint(Paint.ANTI_ALIAS_FLAG); border.setColor(COLOR_PRIMARY); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2.4f*density());
            canvas.drawRoundRect(new RectF(1.5f,1.5f,wPx-1.5f,hPx-1.5f), rad24, rad24, border);

            // Línea de 9 metros: discontinua (semicírculo que se abre hacia abajo, portería arriba)
            Paint dash=new Paint(Paint.ANTI_ALIAS_FLAG); dash.setColor(COLOR_PRIMARY); dash.setStyle(Paint.Style.STROKE); dash.setStrokeWidth(2.2f*density());
            dash.setPathEffect(new DashPathEffect(new float[]{9*density(),7*density()},0));
            canvas.drawArc(new RectF(cx-r9,cy-r9,cx+r9,cy+r9), 0, 180, false, dash);

            // Área de 6 metros: semicírculo relleno
            Paint fill6=new Paint(Paint.ANTI_ALIAS_FLAG); fill6.setColor(0xffb6bdc4); fill6.setStyle(Paint.Style.FILL);
            RectF r6Rect=new RectF(cx-r6,cy-r6,cx+r6,cy+r6);
            canvas.drawArc(r6Rect, 0, 180, true, fill6);
            Paint stroke6=new Paint(Paint.ANTI_ALIAS_FLAG); stroke6.setColor(0xff2c3136); stroke6.setStyle(Paint.Style.STROKE); stroke6.setStrokeWidth(2.2f*density());
            canvas.drawArc(r6Rect, 0, 180, false, stroke6);

            // Marca de 7 metros
            Paint mark=new Paint(Paint.ANTI_ALIAS_FLAG); mark.setColor(COLOR_PRIMARY); mark.setStrokeWidth(3f*density());
            float r7=g[4], markLen=9*density();
            int[] p7=courtPoint(cx,cy,r7,90);
            canvas.drawLine(p7[0]-markLen/2f,p7[1], p7[0]+markLen/2f,p7[1], mark);

            // Portería, a caballo sobre la línea de fondo (ahora arriba de la pista)
            float goalW=wPx*0.17f, goalH=hPx*0.07f;
            Paint goalPaint=new Paint(Paint.ANTI_ALIAS_FLAG); goalPaint.setColor(COLOR_PRIMARY); goalPaint.setStyle(Paint.Style.STROKE); goalPaint.setStrokeWidth(2.6f*density());
            canvas.drawRect(new RectF(cx-goalW/2, cy-goalH*0.6f, cx+goalW/2, cy+goalH*0.4f), goalPaint);
        }
    }

    View launchZoneWidget(int wPx,int hPx,StrCallback cb){
        FrameLayout court=new FrameLayout(this);
        court.addView(new CourtView(wPx,hPx), new FrameLayout.LayoutParams(wPx,hPx));

        float[] g=courtGeom(wPx,hPx);
        float cx=g[0], cy=g[1];
        int cw=(int)(64*density()), ch=(int)(42*density());
        for(int i=0;i<ZONAS.length;i++){
            final String zone=ZONAS[i];
            boolean is7="7 metros".equals(zone);
            boolean is6="6 metros".equals(zone);
            float r = ZONA_RADIO_TIPO[i]==0?g[2] : ZONA_RADIO_TIPO[i]==3?g[4] : g[3];
            int[] pt=courtPoint(cx,cy,r,ZONA_ANGULO[i]);
            TextView chip=new TextView(this);
            chip.setText(zoneShort(zone));
            chip.setTextSize(10); chip.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(0xffffffff);
            chip.setBackground(ripple(rounded(is7||is6?COLOR_ACCENT:COLOR_PRIMARY,14),0x55ffffff,14));
            if(is7||is6) chip.setTextColor(COLOR_ACCENT_TEXT);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(cw,ch);
            int left=pt[0]-cw/2, top=pt[1]-ch/2;
            if(left<0) left=0; if(left>wPx-cw) left=wPx-cw;
            if(top<0) top=0; if(top>hPx-ch) top=hPx-ch;
            lp.leftMargin=left; lp.topMargin=top;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v->cb.run(zone));
            court.addView(chip,lp);
        }
        return court;
    }

    // ---- Mapa de calor 3x3 ----
    int lerpColor(int c1,int c2,double t){
        if(t<0)t=0; if(t>1)t=1;
        int a1=(c1>>24)&0xff,r1=(c1>>16)&0xff,g1=(c1>>8)&0xff,b1=c1&0xff;
        int a2=(c2>>24)&0xff,r2=(c2>>16)&0xff,g2=(c2>>8)&0xff,b2=c2&0xff;
        int a=(int)(a1+(a2-a1)*t),r=(int)(r1+(r2-r1)*t),g=(int)(g1+(g2-g1)*t),b=(int)(b1+(b2-b1)*t);
        return (a<<24)|(r<<16)|(g<<8)|b;
    }
    Map<String,Integer> countsFrom(String sql){
        Map<String,Integer> m=new HashMap<>();
        Cursor c=db.q(sql);
        while(c.moveToNext()){ String k=c.getString(0); if(k!=null && !k.isEmpty()) m.put(k, c.getInt(1)); }
        c.close();
        return m;
    }
    // green=true -> degradado verde (eventos positivos: goles a favor, paradas propias)
    // green=false -> degradado rojo (eventos negativos: goles en contra, lanzamientos parados por el rival)
    View heatmap(Map<String,Integer> counts, boolean green){
        int endColor = green ? COLOR_GREEN : COLOR_RED;
        int baseColor = 0xff173252; // azul noche oscuro para las celdas sin datos / poca actividad
        int max=1; for(int v:counts.values()) if(v>max) max=v;
        FrameLayout outer=new FrameLayout(this);
        GradientDrawable outerBg=rounded(COLOR_PRIMARY_DARK,20); outerBg.setStroke((int)(1.2f*density()),COLOR_BORDER);
        outer.setBackground(outerBg);
        int framePad=(int)(7*density());
        outer.setPadding(framePad,framePad,framePad,framePad);
        LinearLayout grid=new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(rounded(0xff081521,14)); grid.setPadding(10,10,10,10);
        int idx=0;
        for(int r=0;r<3;r++){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for(int cIdx=0;cIdx<3;cIdx++){
                String key=GOAL_CELLS[idx];
                int val=counts.containsKey(key)?counts.get(key):0;
                double t=(double)val/max;
                TextView cell=new TextView(this); cell.setText(""+val); cell.setTextSize(18); cell.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                cell.setTextColor(0xffffffff); cell.setGravity(Gravity.CENTER);
                cell.setBackground(rounded(lerpColor(baseColor,endColor,t),12));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,140); lp.weight=1; lp.setMargins(4,4,4,4);
                cell.setLayoutParams(lp);
                row.addView(cell); idx++;
            }
            grid.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        outer.addView(grid,new FrameLayout.LayoutParams(-1,-2));
        return outer;
    }

    // ---- Mapa de eficacia por zona de lanzamiento (semicírculo de pista, solo lectura) ----
    // Muestra, sobre la misma silueta de pista que se usa al registrar el lanzamiento,
    // la fracción "aciertos/intentos" de cada zona coloreada de verde (buena eficacia) a rojo (baja).
    View zoneEfficiencyChart(List<String[]> shots, int wPx, int hPx){
        Map<String,int[]> byZone=new HashMap<>(); // zona -> {total, aciertos}
        for(String z:ZONAS) byZone.put(z,new int[]{0,0});
        for(String[] s:shots){
            String zona=s[1];
            int[] agg=byZone.get(zona);
            if(agg==null) continue;
            agg[0]++;
            if("Parada".equals(s[s.length-1])) agg[1]++;
        }
        FrameLayout court=new FrameLayout(this);
        court.addView(new CourtView(wPx,hPx), new FrameLayout.LayoutParams(wPx,hPx));

        float[] g=courtGeom(wPx,hPx);
        float cx=g[0], cy=g[1];
        int cw=(int)(70*density()), ch=(int)(46*density());
        for(int i=0;i<ZONAS.length;i++){
            String zone=ZONAS[i];
            int[] agg=byZone.get(zone);
            int total=agg[0], aciertos=agg[1];
            double pctVal = total>0 ? (double)aciertos/total : -1;
            int bg = total==0 ? 0x2affffff : lerpColor(COLOR_RED,COLOR_GREEN, Math.max(pctVal,0));

            LinearLayout chip=new LinearLayout(this); chip.setOrientation(LinearLayout.VERTICAL); chip.setGravity(Gravity.CENTER);
            chip.setBackground(rounded(bg,14));
            TextView frac=new TextView(this); frac.setText(total==0?"–":aciertos+"/"+total);
            frac.setTextSize(14); frac.setTypeface(Typeface.DEFAULT,Typeface.BOLD); frac.setTextColor(0xffffffff); frac.setGravity(Gravity.CENTER);
            TextView lbl=new TextView(this); lbl.setText(zoneShort(zone).replace("\n"," "));
            lbl.setTextSize(9); lbl.setTextColor(0xffffffff); lbl.setGravity(Gravity.CENTER); lbl.setPadding(2,2,2,0);
            chip.addView(frac); chip.addView(lbl);

            float r = ZONA_RADIO_TIPO[i]==0?g[2] : ZONA_RADIO_TIPO[i]==3?g[4] : g[3];
            int[] pt=courtPoint(cx,cy,r,ZONA_ANGULO[i]);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(cw,ch);
            int left=pt[0]-cw/2, top=pt[1]-ch/2;
            if(left<0) left=0; if(left>wPx-cw) left=wPx-cw;
            if(top<0) top=0; if(top>hPx-ch) top=hPx-ch;
            lp.leftMargin=left; lp.topMargin=top;
            court.addView(chip,lp);
        }
        return court;
    }

    int[] computeRecord(){
        int jugados=0,ganados=0,empatados=0,perdidos=0,gf=0,gc=0;
        Cursor c=db.q("SELECT goles_favor,goles_contra FROM partidos");
        while(c.moveToNext()){
            int f=c.getInt(0),ct=c.getInt(1); jugados++; gf+=f; gc+=ct;
            if(f>ct)ganados++; else if(f==ct)empatados++; else perdidos++;
        } c.close();
        return new int[]{jugados,ganados,empatados,perdidos,gf,gc};
    }

    // ============================================================
    // CRONÓMETRO DEL PARTIDO
    // ============================================================
    void resetTimer(){ timerAccumulatedMillis=0; timerRunning=false; timerStartMillis=-1; minuteLabel=null; }
    int computeMinute(){
        long elapsed=timerAccumulatedMillis;
        if(timerRunning) elapsed += System.currentTimeMillis()-timerStartMillis;
        return (int)(elapsed/60000)+1;
    }
    int currentMinute(){ return computeMinute(); }
    void toggleTimer(){
        if(timerRunning){ timerAccumulatedMillis += System.currentTimeMillis()-timerStartMillis; timerRunning=false; }
        else { timerStartMillis=System.currentTimeMillis(); timerRunning=true; ensureTicker(); }
        ongoing();
    }
    void ensureTicker(){
        if(tickerStarted) return; tickerStarted=true;
        Runnable r=new Runnable(){ public void run(){
            if(minuteLabel!=null && timerRunning){ minuteLabel.setText("⏱ Minuto "+computeMinute()); }
            tickHandler.postDelayed(this,1000);
        }};
        tickHandler.postDelayed(r,1000);
    }
    void editMinute(){
        EditText e=new EditText(this); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(""+computeMinute());
        new AlertDialog.Builder(this).setTitle("Ajustar minuto").setView(e)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Aceptar",(d,w)->{
                try{
                    int min=Integer.parseInt(e.getText().toString().trim());
                    timerAccumulatedMillis=(long)Math.max(0,min-1)*60000L;
                    if(timerRunning) timerStartMillis=System.currentTimeMillis();
                }catch(Exception ex){}
                ongoing();
            }).show();
    }

    // ============================================================
    // ESTRUCTURA / NAVEGACIÓN
    // ============================================================
    void base(String name,String screenKey){
        currentScreen=screenKey;
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(COLOR_BG);

        LinearLayout header=new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{COLOR_PRIMARY,COLOR_PRIMARY_DARK}));
        header.setPadding(28,44,28,28);
        TextView titleV=new TextView(this); titleV.setText(name); titleV.setTextSize(22); titleV.setTypeface(Typeface.DEFAULT,Typeface.BOLD); titleV.setTextColor(0xffffffff);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);
        header.addView(titleV,titleLp);
        if(screenKey!=null && auth!=null && auth.getCurrentUser()!=null){
            TextView accIcon=new TextView(this); accIcon.setText("👤"); accIcon.setTextSize(20); accIcon.setTextColor(0xffffffff);
            accIcon.setPadding(16,10,16,10);
            accIcon.setOnClickListener(v->account());
            header.addView(accIcon);
        }
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        ScrollView sv=new ScrollView(this);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(20,20,20,24);
        sv.addView(content); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));

        if(screenKey!=null){
            LinearLayout nav=new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setBackgroundColor(COLOR_PRIMARY_DARK); nav.setPadding(6,10,6,10); nav.setElevation(10);
            String[][] items={{"🏠","Inicio","home"},{"▶️","Partido","ongoing"},{"👥","Jugadores","players"},{"📊","Stats","stats"},{"📋","Partidos","matches"}};
            for(String[] it:items){ nav.addView(navItem(it[0],it[1],it[2]),new LinearLayout.LayoutParams(0,-2,1)); }
            root.addView(nav);
        }
        setContentView(root);
    }
    View navItem(String icon,String label,String key){
        boolean active=key.equals(currentScreen);
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
        l.setPadding(6,10,6,10);
        TextView ic=plain(icon,17,active?COLOR_ACCENT:COLOR_MUTED); ic.setGravity(Gravity.CENTER);
        TextView lb=plain(label,10,active?COLOR_ACCENT:COLOR_MUTED); lb.setGravity(Gravity.CENTER); lb.setPadding(0,2,0,0);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2); cp.gravity=Gravity.CENTER;
        l.addView(ic,cp); l.addView(lb,cp);
        if(active){
            View underline=new View(this); underline.setBackground(rounded(COLOR_ACCENT,3));
            LinearLayout.LayoutParams ulp=new LinearLayout.LayoutParams((int)(24*density()),(int)(3*density())); ulp.topMargin=4; ulp.gravity=Gravity.CENTER;
            l.addView(underline,ulp);
        }
        l.setOnClickListener(v->{
            if(key.equals("home"))home();
            else if(key.equals("ongoing"))ongoingSelect();
            else if(key.equals("players"))players();
            else if(key.equals("stats"))stats();
            else if(key.equals("matches"))matches();
        });
        return l;
    }

    // ============================================================
    // INICIO
    // ============================================================
    void home(){
        base("🤾 Balonmano","home");
        add(plain("Control de partidos y estadísticas",15,COLOR_MUTED),18);

        int[] rec=computeRecord();
        LinearLayout rc=card();
        TextView rt=plain("📊  Balance de la temporada",17,0xffffffff); rt.setTypeface(Typeface.DEFAULT,Typeface.BOLD); rt.setPadding(0,0,0,14);
        rc.addView(rt);
        LinearLayout row1=new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL); row1.setGravity(Gravity.CENTER);
        addChip(row1,statChip("Jugados",""+rec[0])); addChip(row1,statChip("Ganados",""+rec[1]));
        addChip(row1,statChip("Empatados",""+rec[2])); addChip(row1,statChip("Perdidos",""+rec[3]));
        rc.addView(row1);
        LinearLayout row2=new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL); row2.setGravity(Gravity.CENTER); row2.setPadding(0,14,0,16);
        addChip(row2,statChip("Goles a favor",""+rec[4])); addChip(row2,statChip("Goles en contra",""+rec[5]));
        addChip(row2,statChip("Diferencia",(rec[4]-rec[5]>=0?"+":"")+(rec[4]-rec[5])));
        rc.addView(row2);
        Button verGlobal=btnGhost("👁  Ver visión global de la temporada"); verGlobal.setOnClickListener(v->globalStats());
        rc.addView(verGlobal);
        add(rc);

        add(navRow("▶️","Partido en curso",COLOR_RED,()->ongoingSelect()),12);
        add(navRow("👥","Jugadores / plantilla",0,()->players()),12);
        add(navRow("📊","Estadísticas",0,()->stats()),12);
        add(navRow("📋","Partidos",0,()->matches()),12);
        add(navRow("👤","Mi cuenta",0,()->account()),18);

        guideSection();
    }

    // ---- Guía de uso rápida, para saber qué hace cada parte de la app ----
    void guideRow(LinearLayout parent,String icon,String titulo,String desc){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0,10,0,10);
        TextView ic=plain(icon,20,COLOR_ACCENT); ic.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-2,-2); ip.rightMargin=14; ip.gravity=Gravity.TOP;
        row.addView(ic,ip);
        LinearLayout txt=new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL);
        TextView t=plain(titulo,14,COLOR_TEXT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView d=plain(desc,13,COLOR_MUTED); d.setPadding(0,2,0,0);
        txt.addView(t); txt.addView(d);
        row.addView(txt,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(row);
    }
    void guideSection(){
        LinearLayout gc=card();
        TextView gt=plain("📘 Guía rápida de la app",17,COLOR_ACCENT); gt.setTypeface(Typeface.DEFAULT,Typeface.BOLD); gt.setPadding(0,0,0,6);
        gc.addView(gt);
        TextView gi=plain("Así funciona cada parte:",13,COLOR_MUTED); gi.setPadding(0,0,0,4);
        gc.addView(gi);
        guideRow(gc,"▶️","Partido en curso","Abre o crea un partido y ve anotando en directo lo que hace cada jugadora: lanzamientos, asistencias, pérdidas, exclusiones… y las paradas o goles de la portería. Un cronómetro lleva el minuto del partido.");
        guideRow(gc,"👥","Jugadores / plantilla","Aquí das de alta a las jugadoras (nombre, dorsal y posición) y puedes desactivarlas o borrarlas. Toca a una jugadora para ver sus estadísticas individuales.");
        guideRow(gc,"📊","Estadísticas","Consulta los datos de cada partido o la visión global de toda la temporada: totales, rankings, mapas de calor de dónde se marca y se recibe, y eficacia por zona de lanzamiento. Se puede descargar cualquier pantalla como PDF.");
        guideRow(gc,"📋","Partidos","Lista de todos los partidos creados, con su resultado. Desde aquí abres uno para seguir anotando o lo borras.");
        guideRow(gc,"👤","Mi cuenta","Inicia sesión para guardar una copia de tus partidos y jugadoras en la nube, y restaurarla en otro dispositivo.");
        add(gc);
    }

    // ============================================================
    // PARTIDOS
    // ============================================================
    void matches(){
        base("📋 Partidos","matches");
        Button n=btn("➕ Nuevo partido"); n.setOnClickListener(v->newMatch()); add(n);
        Cursor c=db.q("SELECT id,equipo,rival,fecha,competicion,goles_favor,goles_contra FROM partidos ORDER BY fecha DESC,id DESC");
        while(c.moveToNext()){
            int id=c.getInt(0);
            LinearLayout rowc=card();
            TextView t=plain(c.getString(1)+"   "+c.getInt(5)+" - "+c.getInt(6)+"   "+c.getString(2),17,COLOR_TEXT);
            t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,0,0,4);
            TextView sub=plain(c.getString(3)+"  ·  "+(c.getString(4)==null?"":c.getString(4)),13,COLOR_MUTED); sub.setPadding(0,0,0,14);
            rowc.addView(t); rowc.addView(sub);
            LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
            Button open=btn("▶️ Abrir"); open.setOnClickListener(v->{currentMatch=id; resetTimer(); ongoing();});
            Button del=btnDanger("🗑️"); del.setOnClickListener(v->confirmDeleteMatch(id));
            actions.addView(open,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams delLp=new LinearLayout.LayoutParams(-2,-2); delLp.leftMargin=10;
            actions.addView(del,delLp);
            rowc.addView(actions);
            add(rowc);
        }
        c.close();
    }
    void confirmDeleteMatch(int id){
        new AlertDialog.Builder(this).setTitle("Borrar partido")
            .setMessage("Se borrarán también sus acciones, lanzamientos y estadísticas asociadas. ¿Continuar?")
            .setNegativeButton("Cancelar",null).setPositiveButton("Borrar",(d,w)->{db.deleteMatch(id); if(currentMatch==id)currentMatch=-1; matches();}).show();
    }
    void newMatch(){
        base("➕ Nuevo partido","matches");
        LinearLayout c=card();
        EditText team=new EditText(this); team.setHint("Mi equipo"); c.addView(team);
        EditText rival=new EditText(this); rival.setHint("Rival"); c.addView(rival);
        EditText comp=new EditText(this); comp.setHint("Competición"); c.addView(comp);
        EditText date=new EditText(this); date.setText(new SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(new Date())); c.addView(date);
        add(c);
        Button save=btn("💾 Crear partido"); save.setOnClickListener(v->{
            if(team.getText().toString().trim().isEmpty()||rival.getText().toString().trim().isEmpty()){toast("Introduce equipo y rival");return;}
            currentMatch=db.insertMatch(team.getText().toString().trim(),rival.getText().toString().trim(),date.getText().toString(),comp.getText().toString());
            resetTimer();
            ongoing();
        }); add(save);
    }

    // ============================================================
    // PARTIDO EN CURSO
    // ============================================================
    void ongoingSelect(){
        base("▶️ Partido en curso","ongoing");
        add(tv("Selecciona el partido",18));
        Cursor c=db.q("SELECT id,equipo,rival,fecha,goles_favor,goles_contra FROM partidos ORDER BY fecha DESC,id DESC");
        while(c.moveToNext()){
            int id=c.getInt(0); Button b=btn(c.getString(1)+"  "+c.getInt(4)+" - "+c.getInt(5)+"  "+c.getString(2));
            b.setOnClickListener(v->{currentMatch=id; resetTimer(); ongoing();}); add(b);
        }
        c.close();
        Button newb=btnGhost("➕ Crear partido"); newb.setOnClickListener(v->newMatch()); add(newb);
    }

    void ongoing(){
        if(currentMatch<0){ongoingSelect();return;}
        base("▶️ Partido en curso","ongoing");

        LinearLayout headerCard=card();
        Cursor m=db.q("SELECT equipo,rival,goles_favor,goles_contra FROM partidos WHERE id="+currentMatch);
        if(m.moveToFirst()){
            TextView teams=plain(m.getString(0)+"  vs  "+m.getString(1),15,COLOR_MUTED); teams.setGravity(Gravity.CENTER); teams.setPadding(0,0,0,6);
            score=plain(m.getInt(2)+" - "+m.getInt(3),34,COLOR_ACCENT); score.setTypeface(Typeface.DEFAULT,Typeface.BOLD); score.setGravity(Gravity.CENTER);
            headerCard.addView(teams); headerCard.addView(score);
        } m.close();

        LinearLayout timerRow=new LinearLayout(this); timerRow.setOrientation(LinearLayout.HORIZONTAL); timerRow.setGravity(Gravity.CENTER); timerRow.setPadding(0,18,0,0);
        minuteLabel=plain("⏱ Minuto "+computeMinute(),17,COLOR_TEXT); minuteLabel.setTypeface(Typeface.DEFAULT,Typeface.BOLD); minuteLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(0,-2,1); mlp.gravity=Gravity.CENTER_VERTICAL;
        timerRow.addView(minuteLabel,mlp);
        Button toggleBtn=btnColor(timerRunning?"⏸ Pausar":"▶️ Seguir", timerRunning?COLOR_AMBER:COLOR_GREEN, 0xffffffff);
        toggleBtn.setPadding(20,14,20,14); toggleBtn.setTextSize(14);
        toggleBtn.setOnClickListener(v->toggleTimer());
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-2,-2); tlp.leftMargin=10;
        timerRow.addView(toggleBtn,tlp);
        Button editBtn=btnGhost("✏️"); editBtn.setPadding(20,14,20,14); editBtn.setTextSize(14); editBtn.setOnClickListener(v->editMinute());
        LinearLayout.LayoutParams elp=new LinearLayout.LayoutParams(-2,-2); elp.leftMargin=8;
        timerRow.addView(editBtn,elp);
        headerCard.addView(timerRow);
        add(headerCard);
        ensureTicker();

        if(selectedPlayer==null) playerStep(); else actionStep();

        add(plain("Últimas acciones",17,COLOR_ACCENT),10);
        Cursor a=db.q("SELECT a.id,a.minuto,a.accion,a.zona,a.resultado,j.nombre,j.dorsal FROM acciones a LEFT JOIN jugadores j ON j.id=a.jugador_id WHERE a.partido_id="+currentMatch+" ORDER BY a.id DESC LIMIT 12");
        while(a.moveToNext()){
            int id=a.getInt(0); String s=a.getInt(1)+"'  #"+a.getInt(6)+" "+a.getString(5)+"  "+a.getString(2);
            if(a.getString(3)!=null&&!a.getString(3).isEmpty())s+=" · "+a.getString(3); if(a.getString(4)!=null)s+=" · "+a.getString(4);
            LinearLayout rowc=new LinearLayout(this); rowc.setOrientation(LinearLayout.HORIZONTAL); rowc.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rowcBg=rounded(COLOR_CARD,12); rowcBg.setStroke((int)(1*density()),COLOR_BORDER); rowc.setBackground(rowcBg); rowc.setPadding(18,12,10,12);
            rowc.addView(plain(s,14,COLOR_TEXT),new LinearLayout.LayoutParams(0,-2,1));
            Button d=btnDanger("🗑️"); d.setPadding(14,10,14,10); d.setOnClickListener(v->{db.deleteAction(id); db.recalc(currentMatch); ongoing();});
            rowc.addView(d,new LinearLayout.LayoutParams(-2,-2));
            add(rowc,8);
        } a.close();
        Cursor lp=db.q("SELECT lp.id,lp.minuto,lp.resultado,j.nombre,j.dorsal FROM lanzamientos_porteria lp LEFT JOIN porteros p ON p.id=lp.portero_id LEFT JOIN jugadores j ON lower(j.nombre)=lower(p.nombre) AND j.dorsal=p.dorsal WHERE lp.partido_id="+currentMatch+" ORDER BY lp.id DESC LIMIT 8");
        while(lp.moveToNext()){
            int id=lp.getInt(0); String s="🧤 "+lp.getInt(1)+"'  #"+lp.getInt(4)+" "+(lp.getString(3)==null?"":lp.getString(3))+" · "+lp.getString(2);
            LinearLayout rowc=new LinearLayout(this); rowc.setOrientation(LinearLayout.HORIZONTAL); rowc.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rowcBg=rounded(COLOR_CARD,12); rowcBg.setStroke((int)(1*density()),COLOR_BORDER); rowc.setBackground(rowcBg); rowc.setPadding(18,12,10,12);
            rowc.addView(plain(s,14,COLOR_TEXT),new LinearLayout.LayoutParams(0,-2,1));
            Button d=btnDanger("🗑️"); d.setPadding(14,10,14,10); d.setOnClickListener(v->{db.deleteShot(id); db.recalc(currentMatch); ongoing();});
            rowc.addView(d,new LinearLayout.LayoutParams(-2,-2));
            add(rowc,8);
        } lp.close();
    }

    void playerStep(){
        add(tv("¿Quién?",19));
        Cursor c=db.q("SELECT id,nombre,dorsal,posicion FROM jugadores WHERE activo=1 ORDER BY dorsal,nombre");
        int count=0; LinearLayout grid=new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL); LinearLayout line=null;
        while(c.moveToNext()){
            if(count%2==0){line=new LinearLayout(this); line.setPadding(0,0,0,10); grid.addView(line);}
            int id=c.getInt(0); String s="#"+c.getInt(2)+"  "+c.getString(1);
            Button b=btn(s); b.setOnClickListener(v->{selectedPlayer=id; ongoing();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.setMargins(0,0,8,0);
            line.addView(b,lp); count++;
        } c.close(); add(grid);
        Button g=btnGhost("🥅  Portería"); g.setOnClickListener(v->goalkeeperStep()); add(g);
    }
    void actionStep(){
        Cursor p=db.q("SELECT nombre,dorsal,posicion FROM jugadores WHERE id="+selectedPlayer); String name=""; int dorsal=0;
        if(p.moveToFirst()){name=p.getString(0);dorsal=p.getInt(1);}p.close();
        add(tv("Jugador: #"+dorsal+" "+name,19));
        Button back=btnGhost("↩️ Cambiar jugador"); back.setOnClickListener(v->{selectedPlayer=null;ongoing();}); add(back);
        add(tv("¿Qué ha hecho?",19));
        // "7 metros" ya no es una acción aparte: es una zona más dentro de "Lanzamiento".
        // El botón "Gol" independiente se ha quitado: un Lanzamiento que acaba en gol ya lo cuenta.
        Object[][] acts={
            {"🎯 Lanzamiento",COLOR_ACCENT,COLOR_ACCENT_TEXT},
            {"🤝 Asistencia",COLOR_PRIMARY,0xffffffff},
            {"❌ Pérdida",COLOR_PRIMARY,0xffffffff},
            {"🔄 Recuperación",COLOR_PRIMARY,0xffffffff},
            {"⚔️ 1x1 ganado",COLOR_PRIMARY,0xffffffff},
            {"🛡️ 1x1 perdido",COLOR_PRIMARY,0xffffffff},
            {"🟥 Exclusión",COLOR_PRIMARY,0xffffffff}
        };
        for(Object[] x:acts){Button b=btnColor((String)x[0],(int)x[1],(int)x[2]); b.setOnClickListener(v->chooseAction((String)x[0])); add(b);}
    }
    void chooseAction(String x){
        String clean=x.replaceAll("^[^A-Za-zÁÉÍÓÚÜÑ0-9]+","").trim();
        if(clean.equals("Lanzamiento")){ chooseZone(); return; }
        if(clean.equals("Exclusión")){ chooseExclusionType(); return; }
        finishAction(clean,"","Éxito");
    }
    // ---- Exclusión: elegir el tipo de sanción ----
    void chooseExclusionType(){
        base("🟥 Exclusión","ongoing");
        add(tv("¿Qué tipo de exclusión?",19));
        for(String t:TIPOS_EXCLUSION){
            Button b=btn(t); b.setOnClickListener(v->finishAction("Exclusión",t,"Éxito")); add(b);
        }
        Button back=btnGhost("↩️ Volver a acciones"); back.setOnClickListener(v->ongoing()); add(back);
    }

    // ---- Lanzamiento: pantalla propia (igual que en el flujo de Portería) ----
    void chooseZone(){
        base("🎯 Lanzamiento","ongoing");
        add(tv("¿Desde dónde ha lanzado? Toca la posición en la pista",17));
        int[] cs=courtSizePx(); int wPx=cs[0], hPx=cs[1];
        View court=launchZoneWidget(wPx,hPx,z->{pendingZone=z; chooseLanzamientoResultado();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(wPx,hPx); lp.gravity=Gravity.CENTER_HORIZONTAL; lp.bottomMargin=16;
        content.addView(court,lp);
        Button back=btnGhost("↩️ Volver a acciones"); back.setOnClickListener(v->{pendingZone=null; ongoing();}); add(back);
    }
    void chooseLanzamientoResultado(){
        boolean es7m = "7 metros".equals(pendingZone);
        base(es7m?"7️⃣ 7 metros":"🎯 Lanzamiento","ongoing");
        add(tv("¿Cómo ha terminado el lanzamiento?",19));
        Button gol=btnColor("⚽ Gol",COLOR_ACCENT,COLOR_ACCENT_TEXT);
        gol.setOnClickListener(v->{ if(es7m) finishAction("7m gol","7 metros","Gol"); else finishAction("Lanzamiento",pendingZone,"Gol"); });
        add(gol);
        Button parada=btnColor("🧤 Parada",COLOR_TEAL,0xffffffff);
        parada.setOnClickListener(v->{ if(es7m) finishAction("7m lanzamiento","7 metros","Parada"); else finishAction("Lanzamiento",pendingZone,"Parada"); });
        add(parada);
        Button fallo=btnColor("❌ Fallo",COLOR_GRAY,0xffffffff);
        fallo.setOnClickListener(v->{ if(es7m) finishAction("7m lanzamiento","7 metros","Fallo"); else finishAction("Lanzamiento",pendingZone,"Fallo"); });
        add(fallo);
    }

    void finishAction(String action,String zone,String result){
        // Tanto los goles como las paradas (lanzamiento detenido por la portera rival)
        // necesitan indicar a qué zona de la portería iba el balón, para poder
        // construir los mapas de calor de goles a favor y de lanzamientos parados.
        boolean necesitaZona = "Gol".equals(result) || "Parada".equals(result);
        if(necesitaZona){
            pendingFinalAction=action; pendingFinalZone=zone; pendingFinalResult=result;
            selectGoalZonePlayer();
        } else {
            insertActionNow(action,zone,result,"");
        }
    }
    void selectGoalZonePlayer(){
        boolean esGol = "Gol".equals(pendingFinalResult);
        base(esGol?"⚽ ¿Dónde ha marcado?":"🧤 ¿Dónde iba el balón?","ongoing");
        add(tv(esGol?"Toca la zona de la portería donde ha entrado el balón":"Toca la zona de la portería a la que iba el balón cuando lo pararon",15));
        add(goalGridWidget(z->insertActionNow(pendingFinalAction,pendingFinalZone,pendingFinalResult,z)));
        Button skip=btnGhost("➡️ Omitir zona"); skip.setOnClickListener(v->insertActionNow(pendingFinalAction,pendingFinalZone,pendingFinalResult,"")); add(skip);
    }
    void insertActionNow(String action,String zone,String result,String zonaGol){
        int minute=currentMinute();
        db.insertAction(currentMatch,selectedPlayer,minute,action,zone,result,zonaGol);
        db.recalc(currentMatch);
        selectedPlayer=null; pendingAction=null; pendingZone=null;
        pendingFinalAction=null; pendingFinalZone=null; pendingFinalResult=null;
        ongoing();
    }

    void goalkeeperStep(){
        base("🥅 Portería · Partido","ongoing");
        add(tv("Selecciona portero",19));
        Cursor c=db.q("SELECT id,nombre,dorsal FROM jugadores WHERE activo=1 AND lower(posicion) LIKE '%porter%' ORDER BY dorsal");
        while(c.moveToNext()){
            int id=c.getInt(0); Button b=btn("#"+c.getInt(2)+"  "+c.getString(1)); b.setOnClickListener(v->keeperZone(id)); add(b);
        } c.close();
        Button back=btnGhost("↩️ Volver"); back.setOnClickListener(v->ongoing()); add(back);
    }
    // La zona de lanzamiento de la portería se elige con el mismo semicírculo
    // que usan las jugadoras de campo (ya no es una lista de botones).
    void keeperZone(int playerId){
        base("🥅 Zona de lanzamiento","ongoing");
        add(tv("¿Desde dónde ha lanzado el equipo rival? Toca la posición en la pista",17));
        int[] cs=courtSizePx(); int wPx=cs[0], hPx=cs[1];
        View court=launchZoneWidget(wPx,hPx,z->keeperGoalZone(playerId,z));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(wPx,hPx); lp.gravity=Gravity.CENTER_HORIZONTAL; lp.bottomMargin=16;
        content.addView(court,lp);
        Button back=btnGhost("↩️ Volver"); back.setOnClickListener(v->ongoing()); add(back);
    }
    // Se toca la portería para indicar la zona; ya no hace falta un paso aparte de "Dirección"
    // ni de "Tipo de lanzamiento" (se ha eliminado, no interesa para las estadísticas).
    void keeperGoalZone(int playerId,String zone){
        base("🥅 Portería","ongoing");
        add(tv("Toca la zona de la portería a la que ha ido el lanzamiento",15));
        add(goalGridWidget(z->keeperResult(playerId,zone,z)));
        Button skip=btnGhost("➡️ Omitir zona"); skip.setOnClickListener(v->keeperResult(playerId,zone,"")); add(skip);
    }
    void keeperResult(int playerId,String zone,String dir){
        base("🥅 Resultado","ongoing");
        Button stop=btnColor("🧤 Parada",COLOR_TEAL,0xffffffff); stop.setOnClickListener(v->insertKeeper(playerId,zone,dir,"Parada",dir)); add(stop);
        Button goal=btnDanger("⚽ Gol recibido"); goal.setOnClickListener(v->insertKeeper(playerId,zone,dir,"Gol",dir)); add(goal);
        Button fallo=btnColor("❌ Fallo",COLOR_GRAY,0xffffffff); fallo.setOnClickListener(v->insertKeeper(playerId,zone,dir,"Fallo",dir)); add(fallo);
    }
    void insertKeeper(int playerId,String zone,String dir,String result,String zonaGol){
        int porteroId=db.ensurePortero(playerId);
        db.insertShot(currentMatch,porteroId,zone,dir,result,currentMinute(),zonaGol);
        db.recalc(currentMatch);
        ongoing();
    }

    // ============================================================
    // JUGADORAS
    // ============================================================
    void players(){
        base("👥 Jugadores","players");
        Button addb=btn("➕ Añadir jugador"); addb.setOnClickListener(v->addPlayer()); add(addb);
        Cursor c=db.q("SELECT id,nombre,dorsal,posicion,activo FROM jugadores ORDER BY dorsal,nombre");
        while(c.moveToNext()){
            final int id=c.getInt(0); final String nombre=c.getString(1); int dorsal=c.getInt(2); String pos=c.getString(3); final boolean activo=c.getInt(4)==1;

            LinearLayout cardc=card();
            LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
            TextView bd=plain("#"+dorsal,15,0xffffffff); bd.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            bd.setBackground(rounded(activo?COLOR_PRIMARY:COLOR_MUTED,20)); bd.setPadding(20,10,20,10); bd.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bdlp=new LinearLayout.LayoutParams(-2,-2); bdlp.rightMargin=16;
            head.addView(bd,bdlp);
            LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
            TextView t=plain(nombre,17,COLOR_TEXT); t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            TextView sub=plain((pos==null?"":pos)+(activo?"":"  ·  inactivo"),13,COLOR_MUTED); sub.setPadding(0,2,0,0);
            info.addView(t); info.addView(sub);
            info.setOnClickListener(v->playerStats(id));
            head.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            cardc.addView(head);

            LinearLayout actionsRow=new LinearLayout(this); actionsRow.setOrientation(LinearLayout.HORIZONTAL); actionsRow.setPadding(0,16,0,0);
            Button statsB=btnGhost("📊 Stats"); statsB.setOnClickListener(v->playerStats(id));
            Button toggleB=btnGhost(activo?"🔕 Desactivar":"↩️ Activar"); toggleB.setOnClickListener(v->{
                if(activo) db.deactivatePlayer(id); else db.activatePlayer(id);
                players();
            });
            Button delB=btnDanger("🗑️"); delB.setOnClickListener(v->confirmDeletePlayer(id,nombre));
            LinearLayout.LayoutParams s1=new LinearLayout.LayoutParams(0,-2,1); s1.rightMargin=8;
            LinearLayout.LayoutParams s2=new LinearLayout.LayoutParams(0,-2,1); s2.rightMargin=8;
            LinearLayout.LayoutParams s3=new LinearLayout.LayoutParams(-2,-2);
            actionsRow.addView(statsB,s1); actionsRow.addView(toggleB,s2); actionsRow.addView(delB,s3);
            cardc.addView(actionsRow);

            add(cardc);
        } c.close();
        add(plain("La portería se registra dentro de «Partido en curso». No existe una plantilla de porteros separada.",13,COLOR_MUTED));
    }
    void confirmDeletePlayer(int id,String nombre){
        new AlertDialog.Builder(this).setTitle("Borrar jugador")
            .setMessage("Se borrará definitivamente a "+nombre+" y todas sus acciones registradas en los partidos. Esta acción no se puede deshacer.\n\nSi prefieres conservar su historial, usa «Desactivar» en su lugar.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Borrar",(d,w)->{ db.deletePlayer(id); players(); })
            .show();
    }
    void addPlayer(){
        base("➕ Jugador","players");
        LinearLayout c=card();
        EditText n=new EditText(this);n.setHint("Nombre");c.addView(n);
        EditText d=new EditText(this);d.setHint("Dorsal");d.setInputType(2);c.addView(d);

        TextView posLabel=plain("Posición",13,COLOR_MUTED); posLabel.setPadding(4,18,4,4); c.addView(posLabel);
        Spinner posSpinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, POSICIONES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        posSpinner.setAdapter(adapter);
        c.addView(posSpinner);
        add(c);
        Button b=btn("💾 Guardar");b.setOnClickListener(v->{
            try{
                String posicion=(String)posSpinner.getSelectedItem();
                db.insertPlayer(n.getText().toString(),Integer.parseInt(d.getText().toString()),posicion);
                players();
            }catch(Exception e){toast("Revisa nombre y dorsal");}
        });add(b);
    }

    // ============================================================
    // ESTADÍSTICAS
    // ============================================================
    void stats(){
        base("📊 Estadísticas","stats");
        Button gl=btn("🌍 Visión global de la temporada"); gl.setOnClickListener(v->globalStats()); add(gl);
        add(plain("Estadísticas por partido",15,COLOR_MUTED),10);
        Cursor c=db.q("SELECT id,equipo,rival,fecha,goles_favor,goles_contra FROM partidos ORDER BY fecha DESC,id DESC");
        while(c.moveToNext()){
            int id=c.getInt(0); Button b=btnGhost(c.getString(1)+"  "+c.getInt(4)+" - "+c.getInt(5)+"  "+c.getString(2)); b.setOnClickListener(v->statsMatch(id));add(b);
        }c.close();
    }

    // ---- Visión global de la temporada ----
    void globalStats(){
        base("🌍 Visión global","stats");
        exportPdfButton("estadisticas_temporada");
        int[] rec=computeRecord();
        List<String[]> recordRow=new ArrayList<>();
        recordRow.add(new String[]{""+rec[0],""+rec[1],""+rec[2],""+rec[3],""+rec[4],""+rec[5],(rec[4]-rec[5]>=0?"+":"")+(rec[4]-rec[5])});
        sectionTable("📅 Balance de la temporada", new String[]{"Jugados","Ganados","Empat.","Perdidos","GF","GC","Dif."}, recordRow);

        List<String[]> resultados=new ArrayList<>();
        Cursor rc=db.q("SELECT fecha,rival,goles_favor,goles_contra FROM partidos ORDER BY fecha DESC,id DESC");
        while(rc.moveToNext()){
            int f=rc.getInt(2),ct=rc.getInt(3);
            String res=f>ct?"✅ V":(f==ct?"➖ E":"❌ D");
            resultados.add(new String[]{rc.getString(0),rc.getString(1),f+" - "+ct,res});
        } rc.close();
        if(resultados.isEmpty()) sectionText("📋 Resultados","Todavía no hay partidos registrados.");
        else sectionTable("📋 Resultados", new String[]{"Fecha","Rival","Resultado","·"}, resultados);

        // Ranking de goleadoras (temporada completa)
        List<Row> goleadoras=new ArrayList<>();
        Cursor jc=db.q(
            "SELECT j.dorsal,j.nombre,"+
            "SUM(CASE WHEN a.accion='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Lanzamiento' AND a.resultado='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='7m gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='7m lanzamiento' THEN 1 ELSE 0 END)"+
            " FROM jugadores j LEFT JOIN acciones a ON a.jugador_id=j.id"+
            " WHERE j.activo=1 GROUP BY j.id ORDER BY j.dorsal"
        );
        while(jc.moveToNext()){
            int golesDirectos=jc.getInt(2), lanzTotal=jc.getInt(3), lanzGol=jc.getInt(4);
            int m7g=jc.getInt(5), m7l=jc.getInt(6);
            int golesLanz=golesDirectos+lanzGol, lanzNormalTotal=golesDirectos+lanzTotal, m7Total=m7g+m7l;
            int totalGoles=golesLanz+m7g, totalIntentos=lanzNormalTotal+m7Total;
            goleadoras.add(new Row(new String[]{"#"+jc.getInt(0)+" "+jc.getString(1), ""+totalGoles, ""+totalIntentos, pct(totalGoles,totalIntentos)}, totalGoles));
        }
        jc.close();
        Collections.sort(goleadoras);
        List<String[]> goleadorasRows=new ArrayList<>(); for(Row r:goleadoras) goleadorasRows.add(r.cells);
        sectionTable("🏆 Ranking de goleadores (temporada)", new String[]{"Jugador","Goles","Lanz.","% Éxito"}, goleadorasRows);

        // Ranking de porteras
        List<Row> porteras=new ArrayList<>();
        Cursor pc=db.q(
            "SELECT p.dorsal,p.nombre,COUNT(lp.id),"+
            "SUM(CASE WHEN lp.resultado='Parada' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN lp.resultado='Gol' THEN 1 ELSE 0 END)"+
            " FROM porteros p LEFT JOIN lanzamientos_porteria lp ON lp.portero_id=p.id"+
            " WHERE p.activo=1 GROUP BY p.id ORDER BY p.dorsal"
        );
        while(pc.moveToNext()){
            int total=pc.getInt(2), paradas=pc.getInt(3), goles=pc.getInt(4);
            if(total>0){
                double pctVal=100.0*paradas/total;
                porteras.add(new Row(new String[]{"#"+pc.getInt(0)+" "+pc.getString(1), ""+total, ""+paradas, ""+goles, pct(paradas,total)}, pctVal));
            }
        }
        pc.close();
        Collections.sort(porteras);
        List<String[]> porterasRows=new ArrayList<>(); for(Row r:porteras) porterasRows.add(r.cells);
        if(porterasRows.isEmpty()) sectionText("🧤 Ranking de porteros (temporada)","Todavía no hay lanzamientos de portería registrados.");
        else sectionTable("🧤 Ranking de porteros (temporada)", new String[]{"Portero","Lanz.","Paradas","Goles","% Paradas"}, porterasRows);

        Map<String,Integer> goleados=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM acciones WHERE zona_gol IS NOT NULL AND zona_gol!='' AND "+
            "(accion='Gol' OR (accion='Lanzamiento' AND resultado='Gol') OR accion='7m gol') GROUP BY zona_gol"
        );
        Map<String,Integer> paradasPropias=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM lanzamientos_porteria WHERE resultado='Parada' AND zona_gol IS NOT NULL AND zona_gol!='' GROUP BY zona_gol"
        );
        Map<String,Integer> lanzParados=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM acciones WHERE zona_gol IS NOT NULL AND zona_gol!='' AND "+
            "((accion='Lanzamiento' AND resultado='Parada') OR (accion='7m lanzamiento' AND resultado='Parada')) GROUP BY zona_gol"
        );
        Map<String,Integer> recibidos=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM lanzamientos_porteria WHERE resultado='Gol' AND zona_gol IS NOT NULL AND zona_gol!='' GROUP BY zona_gol"
        );
        groupHeader("🟢 A favor");
        if(goleados.isEmpty()) sectionText("🔥 Zona de los goles marcados","Todavía no hay goles con zona de portería registrada.");
        else section("🔥 Zona de los goles marcados", heatmap(goleados,true));
        if(paradasPropias.isEmpty()) sectionText("🔥 Zona de las paradas del portero","Todavía no hay paradas con zona registrada.");
        else section("🔥 Zona de las paradas del portero", heatmap(paradasPropias,true));

        groupHeader("🔴 En contra");
        if(lanzParados.isEmpty()) sectionText("🔥 Zona de lanzamientos parados (sin gol)","Todavía no hay lanzamientos parados con zona registrada.");
        else section("🔥 Zona de lanzamientos parados (sin gol)", heatmap(lanzParados,false));
        if(recibidos.isEmpty()) sectionText("🔥 Zona de los goles recibidos","Todavía no hay goles recibidos con zona registrada.");
        else section("🔥 Zona de los goles recibidos", heatmap(recibidos,false));
    }

    // ---- Estadísticas de un partido (tabla transpuesta: filas=estadística, columnas=jugadoras) ----
    void statsMatch(int id){
        base("📊 Estadísticas","stats");
        String rivalName="";
        Cursor m=db.q("SELECT equipo,rival,goles_favor,goles_contra FROM partidos WHERE id="+id);
        if(m.moveToFirst()){
            rivalName=m.getString(1);
            LinearLayout hc=card();
            TextView t=plain(m.getString(0)+"   "+m.getInt(2)+" - "+m.getInt(3)+"   "+m.getString(1),20,COLOR_ACCENT);
            t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setGravity(Gravity.CENTER);
            hc.addView(t); add(hc);
        }
        m.close();
        exportPdfButton("estadisticas_vs_"+rivalName);

        groupHeader("🏐 Jugadores");

        List<String> headerNames=new ArrayList<>(); headerNames.add("Estadística");
        List<String> labels=null;
        List<List<String>> playerCols=new ArrayList<>();
        int totGoles=0,totIntentos=0,totAsist=0,totPerd=0,totRecup=0;

        Cursor c=db.q(
            "SELECT j.id,j.dorsal,j.nombre,"+
            "SUM(CASE WHEN a.accion='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Lanzamiento' AND a.resultado='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Asistencia' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Pérdida' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Recuperación' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Exclusión' AND a.zona IN ('Tarjeta amarilla','Tarjeta azul','Tarjeta roja') THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='7m gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='7m lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN a.accion='Exclusión' THEN 1 ELSE 0 END)"+
            " FROM jugadores j LEFT JOIN acciones a ON a.jugador_id=j.id AND a.partido_id="+id+
            " WHERE j.activo=1 GROUP BY j.id ORDER BY j.dorsal"
        );
        while(c.moveToNext()){
            int golesDirectos=c.getInt(3), lanzTotal=c.getInt(4), lanzGol=c.getInt(5);
            int asist=c.getInt(6), perd=c.getInt(7), recup=c.getInt(8);
            int tarjetas=c.getInt(9);
            int m7g=c.getInt(10), m7lAttempt=c.getInt(11), exclus=c.getInt(12);

            List<String[]> rows=statRows(golesDirectos,lanzTotal,lanzGol,asist,perd,recup,m7g,m7lAttempt,exclus,tarjetas);
            if(labels==null){ labels=new ArrayList<>(); for(String[] r:rows) labels.add(r[0]); }
            headerNames.add("#"+c.getInt(1));
            List<String> vals=new ArrayList<>(); for(String[] r:rows) vals.add(r[1]);
            playerCols.add(vals);

            totGoles += golesDirectos+lanzGol+m7g;
            totIntentos += (golesDirectos+lanzTotal)+(m7g+m7lAttempt);
            totAsist+=asist; totPerd+=perd; totRecup+=recup;
        }
        c.close();

        List<String[]> finalRows=new ArrayList<>();
        if(labels!=null){
            for(int i=0;i<labels.size();i++){
                String[] rowArr=new String[1+playerCols.size()];
                rowArr[0]=labels.get(i);
                for(int p=0;p<playerCols.size();p++) rowArr[1+p]=playerCols.get(p).get(i);
                finalRows.add(rowArr);
            }
        }
        if(finalRows.isEmpty()) sectionText("👥 Estadísticas de jugadores","No hay jugadores activos.");
        else sectionTable("👥 Estadísticas de jugadores", headerNames.toArray(new String[0]), finalRows);

        List<String[]> resumenOf=new ArrayList<>();
        resumenOf.add(new String[]{""+totGoles, ""+totIntentos, pct(totGoles,totIntentos), ""+totAsist, ""+totPerd, ""+totRecup});
        sectionTable("📊 Resumen ofensivo", new String[]{"Goles","Lanz.","% Éxito","Asist.","Pérdidas","Recup."}, resumenOf);

        List<String[]> acciones=new ArrayList<>();
        Cursor ac=db.q("SELECT a.minuto,j.dorsal,j.nombre,a.accion,a.zona,a.resultado FROM acciones a LEFT JOIN jugadores j ON j.id=a.jugador_id WHERE a.partido_id="+id+" ORDER BY a.minuto,a.id");
        while(ac.moveToNext()){
            acciones.add(new String[]{""+ac.getInt(0), "#"+ac.getInt(1)+" "+ac.getString(2), ac.getString(3), ac.getString(4)==null?"":ac.getString(4), ac.getString(5)==null?"":ac.getString(5)});
        }
        ac.close();
        if(acciones.isEmpty()) sectionText("📋 Acciones registradas","Todavía no hay acciones registradas en este partido.");
        else sectionTable("📋 Acciones registradas", new String[]{"Min","Jugador","Acción","Zona","Resultado"}, acciones);

        Map<String,Integer> zgGoles=new HashMap<>();
        Cursor zg=db.q("SELECT zona_gol,COUNT(*) FROM acciones WHERE partido_id="+id+" AND zona_gol IS NOT NULL AND zona_gol!='' AND (accion='Gol' OR (accion='Lanzamiento' AND resultado='Gol') OR accion='7m gol') GROUP BY zona_gol");
        while(zg.moveToNext()){ String k=zg.getString(0); if(k!=null&&!k.isEmpty()) zgGoles.put(k,zg.getInt(1)); } zg.close();
        if(!zgGoles.isEmpty()) section("🔥 Zona de los goles marcados", heatmap(zgGoles,true));

        Map<String,Integer> zgParados=new HashMap<>();
        Cursor zp=db.q("SELECT zona_gol,COUNT(*) FROM acciones WHERE partido_id="+id+" AND zona_gol IS NOT NULL AND zona_gol!='' AND ((accion='Lanzamiento' AND resultado='Parada') OR (accion='7m lanzamiento' AND resultado='Parada')) GROUP BY zona_gol");
        while(zp.moveToNext()){ String k=zp.getString(0); if(k!=null&&!k.isEmpty()) zgParados.put(k,zp.getInt(1)); } zp.close();
        if(!zgParados.isEmpty()) section("🔥 Zona de lanzamientos parados (sin gol)", heatmap(zgParados,false));

        groupHeader("🧤 Porteros");

        List<String[]> shots=new ArrayList<>();
        Cursor lp=db.q("SELECT portero_id,zona,direccion,resultado FROM lanzamientos_porteria WHERE partido_id="+id);
        while(lp.moveToNext()) shots.add(new String[]{lp.getString(0),lp.getString(1),lp.getString(2),lp.getString(3)});
        lp.close();

        int totalLanzP=shots.size(); int totalParadas=0, totalGolesP=0;
        for(String[] s:shots){ if("Parada".equals(s[3]))totalParadas++; if("Gol".equals(s[3]))totalGolesP++; }
        List<String[]> resumenDef=new ArrayList<>();
        resumenDef.add(new String[]{""+totalLanzP, ""+totalParadas, ""+totalGolesP, pct(totalParadas,totalLanzP)});
        sectionTable("🥅 Resumen defensivo", new String[]{"Lanzamientos","Paradas","Goles recibidos","% Paradas"}, resumenDef);

        List<String[]> filasPort=new ArrayList<>();
        Cursor pk=db.q("SELECT DISTINCT p.id,p.nombre,p.dorsal FROM lanzamientos_porteria lp INNER JOIN porteros p ON p.id=lp.portero_id WHERE lp.partido_id="+id+" ORDER BY p.dorsal");
        while(pk.moveToNext()){
            String pid=""+pk.getInt(0); int t=0,par=0,gol=0;
            for(String[] s:shots){ if(pid.equals(s[0])){ t++; if("Parada".equals(s[3]))par++; if("Gol".equals(s[3]))gol++; } }
            filasPort.add(new String[]{"#"+pk.getInt(2)+" "+pk.getString(1), ""+t, ""+par, ""+gol, pct(par,t)});
        }
        pk.close();
        if(filasPort.isEmpty()) sectionText("🥅 Estadísticas por portero","Todavía no hay estadísticas de portería para este partido.");
        else sectionTable("🥅 Estadísticas por portero", new String[]{"Portero","Lanzamientos","Paradas","Goles","% Paradas"}, filasPort);

        int[] cs=courtSizePx(); int wPx=cs[0], hPx=cs[1];
        section("📍 Mapa de eficacia por zona de lanzamiento", zoneEfficiencyChart(shots,wPx,hPx));
        sectionTable("📍 Lanzamientos por zona", new String[]{"Zona","Lanzamientos","Paradas","Goles","% Paradas"}, byCategory(shots,1,ZONAS));
        sectionTable("↗️ Lanzamientos por zona de portería", new String[]{"Zona portería","Lanzamientos","Paradas","Goles","% Paradas"}, byCategory(shots,2,GOAL_CELLS));

        Map<String,Integer> zgParadasProp=new HashMap<>();
        Cursor zpp=db.q("SELECT zona_gol,COUNT(*) FROM lanzamientos_porteria WHERE partido_id="+id+" AND resultado='Parada' AND zona_gol IS NOT NULL AND zona_gol!='' GROUP BY zona_gol");
        while(zpp.moveToNext()){ String k=zpp.getString(0); if(k!=null&&!k.isEmpty()) zgParadasProp.put(k,zpp.getInt(1)); } zpp.close();
        if(!zgParadasProp.isEmpty()) section("🔥 Zona de las paradas del portero", heatmap(zgParadasProp,true));

        Map<String,Integer> zgRecibidos=new HashMap<>();
        Cursor zr=db.q("SELECT zona_gol,COUNT(*) FROM lanzamientos_porteria WHERE partido_id="+id+" AND resultado='Gol' AND zona_gol IS NOT NULL AND zona_gol!='' GROUP BY zona_gol");
        while(zr.moveToNext()){ String k=zr.getString(0); if(k!=null&&!k.isEmpty()) zgRecibidos.put(k,zr.getInt(1)); } zr.close();
        if(!zgRecibidos.isEmpty()) section("🔥 Zona de los goles recibidos", heatmap(zgRecibidos,false));

        if(totalLanzP==0) add(plain("Este partido todavía no tiene lanzamientos de portería registrados.",13,COLOR_MUTED));
    }

    List<String[]> byCategory(List<String[]> shots, int idx, String[] categorias){
        List<String[]> rows=new ArrayList<>();
        for(String cat:categorias){
            int total=0, paradas=0, goles=0;
            for(String[] s:shots){
                String resultado=s[s.length-1];
                if(cat.equals(s[idx])){
                    total++;
                    if("Parada".equals(resultado)) paradas++;
                    if("Gol".equals(resultado)) goles++;
                }
            }
            rows.add(new String[]{cat, ""+total, ""+paradas, ""+goles, pct(paradas,total)});
        }
        return rows;
    }

    // ---- Estadísticas de una jugadora ----
    void playerStats(int playerId){ playerStats(playerId,"global",null); }
    void playerStats(int playerId,String mode,Integer matchId){
        Cursor p=db.q("SELECT nombre,dorsal,posicion,activo FROM jugadores WHERE id="+playerId);
        String name="",pos=""; int dorsal=0; boolean activo=true;
        if(p.moveToFirst()){name=p.getString(0);dorsal=p.getInt(1);pos=p.getString(2);activo=p.getInt(3)==1;} p.close();
        base("👤 #"+dorsal+" "+name,"players");
        add(plain((pos==null?"":pos)+(activo?"":"  ·  inactivo"),14,COLOR_MUTED),16);
        exportPdfButton("estadisticas_"+name);

        // ---- Selector: estadísticas globales o de un partido concreto ----
        boolean esGlobal="global".equals(mode);
        LinearLayout toggle=new LinearLayout(this); toggle.setOrientation(LinearLayout.HORIZONTAL); toggle.setPadding(0,0,0,4);
        Button gBtn = esGlobal ? btnColor("🌍 Global",COLOR_ACCENT,COLOR_ACCENT_TEXT) : btnGhost("🌍 Global");
        gBtn.setTextSize(14); gBtn.setOnClickListener(v->playerStats(playerId,"global",null));
        Button pBtn = !esGlobal ? btnColor("📅 Por partido",COLOR_ACCENT,COLOR_ACCENT_TEXT) : btnGhost("📅 Por partido");
        pBtn.setTextSize(14); pBtn.setOnClickListener(v->playerStats(playerId,"partido",null));
        LinearLayout.LayoutParams tlp1=new LinearLayout.LayoutParams(0,-2,1); tlp1.rightMargin=8;
        LinearLayout.LayoutParams tlp2=new LinearLayout.LayoutParams(0,-2,1);
        toggle.addView(gBtn,tlp1); toggle.addView(pBtn,tlp2);
        add(toggle,18);

        if(!esGlobal){
            playerStatsPorPartido(playerId,name,matchId);
            return;
        }

        Cursor c=db.q(
            "SELECT "+
            "SUM(CASE WHEN accion='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Lanzamiento' AND resultado='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Asistencia' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Pérdida' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Recuperación' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Exclusión' AND zona IN ('Tarjeta amarilla','Tarjeta azul','Tarjeta roja') THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='7m gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='7m lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Exclusión' THEN 1 ELSE 0 END),"+
            "COUNT(DISTINCT partido_id)"+
            " FROM acciones WHERE jugador_id="+playerId
        );
        List<String[]> totales=new ArrayList<>();
        int partidosJugados=0;
        if(c.moveToFirst()){
            int golesDirectos=c.getInt(0), lanzTotal=c.getInt(1), lanzGol=c.getInt(2);
            int asist=c.getInt(3), perd=c.getInt(4), recup=c.getInt(5);
            int tarjetas=c.getInt(6);
            int m7g=c.getInt(7), m7lAttempt=c.getInt(8), exclus=c.getInt(9);
            partidosJugados=c.getInt(10);
            totales=statRows(golesDirectos,lanzTotal,lanzGol,asist,perd,recup,m7g,m7lAttempt,exclus,tarjetas);
        }
        c.close();
        totales.add(0,new String[]{"🗓️ Partidos jugados", ""+partidosJugados});
        sectionTable("📊 Totales (todos los partidos)", new String[]{"Estadística","Valor"}, totales);

        boolean esPortera = pos!=null && pos.toLowerCase(Locale.getDefault()).contains("porter");

        // Mapas de calor de la jugadora: sus goles marcados (y, si es portera,
        // también sus paradas y los goles que ha encajado).
        Map<String,Integer> zonaGoles=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM acciones WHERE jugador_id="+playerId+" AND zona_gol IS NOT NULL AND zona_gol!='' AND "+
            "(accion='Gol' OR (accion='Lanzamiento' AND resultado='Gol') OR accion='7m gol') GROUP BY zona_gol"
        );
        if(!zonaGoles.isEmpty()) section("🔥 Zona de sus goles", heatmap(zonaGoles,true));

        Map<String,Integer> zonaParadasFallidas=countsFrom(
            "SELECT zona_gol,COUNT(*) FROM acciones WHERE jugador_id="+playerId+" AND zona_gol IS NOT NULL AND zona_gol!='' AND "+
            "((accion='Lanzamiento' AND resultado='Parada') OR (accion='7m lanzamiento' AND resultado='Parada')) GROUP BY zona_gol"
        );
        if(!zonaParadasFallidas.isEmpty()) section("🔥 Zona de sus lanzamientos parados (sin gol)", heatmap(zonaParadasFallidas,false));

        if(esPortera){
            groupHeader("🧤 Como portero");
            Cursor pid=db.q("SELECT id FROM porteros WHERE lower(nombre)=lower(?) AND dorsal=?", new String[]{name, ""+dorsal});
            List<String[]> shots=new ArrayList<>();
            while(pid.moveToNext()){
                Cursor lp2=db.q("SELECT zona,direccion,resultado FROM lanzamientos_porteria WHERE portero_id="+pid.getInt(0));
                while(lp2.moveToNext()) shots.add(new String[]{null,lp2.getString(0),lp2.getString(1),lp2.getString(2)});
                lp2.close();
            }
            pid.close();
            if(!shots.isEmpty()){
                int t=shots.size(), par=0, gol=0;
                for(String[] s:shots){ if("Parada".equals(s[3]))par++; if("Gol".equals(s[3]))gol++; }
                List<String[]> rowP=new ArrayList<>();
                rowP.add(new String[]{""+t, ""+par, ""+gol, pct(par,t)});
                sectionTable("🥅 Portería (todos los partidos)", new String[]{"Lanzamientos","Paradas","Goles","% Paradas"}, rowP);
                int[] cs2=courtSizePx(); int wPx2=cs2[0], hPx2=cs2[1];
                section("📍 Mapa de eficacia por zona", zoneEfficiencyChart(shots,wPx2,hPx2));
                sectionTable("📍 Por zona", new String[]{"Zona","Lanzamientos","Paradas","Goles","% Paradas"}, byCategory(shots,1,ZONAS));

                Map<String,Integer> zonaParadasPropias=countsFrom(
                    "SELECT lp.zona_gol,COUNT(*) FROM lanzamientos_porteria lp INNER JOIN porteros p ON p.id=lp.portero_id "+
                    "WHERE lower(p.nombre)=lower('"+name.replace("'","''")+"') AND p.dorsal="+dorsal+
                    " AND lp.resultado='Parada' AND lp.zona_gol IS NOT NULL AND lp.zona_gol!='' GROUP BY lp.zona_gol"
                );
                if(!zonaParadasPropias.isEmpty()) section("🔥 Zona de sus paradas", heatmap(zonaParadasPropias,true));

                Map<String,Integer> zonaGolesRecibidos=countsFrom(
                    "SELECT lp.zona_gol,COUNT(*) FROM lanzamientos_porteria lp INNER JOIN porteros p ON p.id=lp.portero_id "+
                    "WHERE lower(p.nombre)=lower('"+name.replace("'","''")+"') AND p.dorsal="+dorsal+
                    " AND lp.resultado='Gol' AND lp.zona_gol IS NOT NULL AND lp.zona_gol!='' GROUP BY lp.zona_gol"
                );
                if(!zonaGolesRecibidos.isEmpty()) section("🔥 Zona de los goles recibidos", heatmap(zonaGolesRecibidos,false));
            }
        }
    }

    // ---- Estadísticas de una jugadora filtradas a un partido concreto ----
    void playerStatsPorPartido(int playerId,String name,Integer matchId){
        if(matchId==null){
            add(tv("Elige un partido",16));
            Cursor pm=db.q("SELECT id,equipo,rival,fecha,goles_favor,goles_contra FROM partidos ORDER BY fecha DESC,id DESC");
            boolean any=false;
            while(pm.moveToNext()){
                any=true;
                int mid=pm.getInt(0);
                Button b=btnGhost(pm.getString(1)+"  "+pm.getInt(4)+" - "+pm.getInt(5)+"  "+pm.getString(2)+"   ·   "+pm.getString(3));
                b.setOnClickListener(v->playerStats(playerId,"partido",mid));
                add(b,10);
            }
            pm.close();
            if(!any) add(plain("Todavía no hay partidos creados.",14,COLOR_MUTED));
            return;
        }
        Button back=btnGhost("↩️ Elegir otro partido"); back.setOnClickListener(v->playerStats(playerId,"partido",null)); add(back,16);

        String rival="",fecha=""; int gf=0,gc=0;
        Cursor m=db.q("SELECT rival,fecha,goles_favor,goles_contra FROM partidos WHERE id="+matchId);
        if(m.moveToFirst()){ rival=m.getString(0); fecha=m.getString(1); gf=m.getInt(2); gc=m.getInt(3); } m.close();
        LinearLayout hc=card();
        TextView ht=plain("vs "+rival+"   "+gf+" - "+gc,17,0xffffffff); ht.setTypeface(Typeface.DEFAULT,Typeface.BOLD); ht.setGravity(Gravity.CENTER);
        TextView hs=plain(fecha,13,COLOR_MUTED); hs.setGravity(Gravity.CENTER); hs.setPadding(0,4,0,0);
        hc.addView(ht); hc.addView(hs); add(hc);

        Cursor c=db.q(
            "SELECT "+
            "SUM(CASE WHEN accion='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Lanzamiento' AND resultado='Gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Asistencia' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Pérdida' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Recuperación' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Exclusión' AND zona IN ('Tarjeta amarilla','Tarjeta azul','Tarjeta roja') THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='7m gol' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='7m lanzamiento' THEN 1 ELSE 0 END),"+
            "SUM(CASE WHEN accion='Exclusión' THEN 1 ELSE 0 END),"+
            "COUNT(*)"+
            " FROM acciones WHERE jugador_id="+playerId+" AND partido_id="+matchId
        );
        List<String[]> filas=new ArrayList<>();
        int totAcc=0;
        if(c.moveToFirst()){
            totAcc=c.getInt(10);
            if(totAcc>0){
                int golesDirectos=c.getInt(0), lanzTotal=c.getInt(1), lanzGol=c.getInt(2);
                int asist=c.getInt(3), perd=c.getInt(4), recup=c.getInt(5);
                int tarjetas=c.getInt(6);
                int m7g=c.getInt(7), m7lAttempt=c.getInt(8), exclus=c.getInt(9);
                filas=statRows(golesDirectos,lanzTotal,lanzGol,asist,perd,recup,m7g,m7lAttempt,exclus,tarjetas);
            }
        }
        c.close();
        if(totAcc==0) sectionText("📊 Estadísticas del partido","Esta jugadora no tiene acciones registradas en este partido.");
        else sectionTable("📊 Estadísticas del partido", new String[]{"Estadística","Valor"}, filas);

        // Si es portera y jugó este partido, también sus datos de portería de ese partido.
        Cursor pInfo=db.q("SELECT nombre,dorsal,posicion FROM jugadores WHERE id="+playerId);
        String pname="",ppos=""; int pdorsal=0;
        if(pInfo.moveToFirst()){ pname=pInfo.getString(0); pdorsal=pInfo.getInt(1); ppos=pInfo.getString(2); } pInfo.close();
        if(ppos!=null && ppos.toLowerCase(Locale.getDefault()).contains("porter")){
            List<String[]> shots=new ArrayList<>();
            Cursor pid=db.q("SELECT id FROM porteros WHERE lower(nombre)=lower(?) AND dorsal=?", new String[]{pname, ""+pdorsal});
            while(pid.moveToNext()){
                Cursor lp2=db.q("SELECT zona,direccion,resultado FROM lanzamientos_porteria WHERE portero_id="+pid.getInt(0)+" AND partido_id="+matchId);
                while(lp2.moveToNext()) shots.add(new String[]{null,lp2.getString(0),lp2.getString(1),lp2.getString(2)});
                lp2.close();
            }
            pid.close();
            if(!shots.isEmpty()){
                int t=shots.size(), par=0, gol=0;
                for(String[] s:shots){ if("Parada".equals(s[3]))par++; if("Gol".equals(s[3]))gol++; }
                List<String[]> rowP=new ArrayList<>();
                rowP.add(new String[]{""+t, ""+par, ""+gol, pct(par,t)});
                sectionTable("🥅 Portería (este partido)", new String[]{"Lanzamientos","Paradas","Goles","% Paradas"}, rowP);
            }
        }
    }

    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    static class DB extends SQLiteOpenHelper {
        static final String NAME="balonmano.db";
        Context ctx;
        DB(Context c){super(c,NAME,null,4);ctx=c;copyIfNeeded();ensureSchema();}
        void copyIfNeeded(){
            File f=ctx.getDatabasePath(NAME); if(f.exists())return; f.getParentFile().mkdirs();
            try(InputStream in=ctx.getAssets().open(NAME);OutputStream out=new FileOutputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}catch(Exception e){throw new RuntimeException(e);}
        }
        // La base de datos que viaja dentro de assets/ no siempre trae marcada la versión
        // interna que espera SQLiteOpenHelper, así que confiar solo en onUpgrade() podía dejar
        // la tabla sin la columna "zona_gol" y provocar que la app se cerrara sola al entrar en
        // Estadísticas. Por eso comprobamos y añadimos las columnas SIEMPRE al arrancar.
        void ensureSchema(){
            SQLiteDatabase d=getWritableDatabase();
            addColumnIfMissing(d,"acciones","zona_gol","TEXT");
            addColumnIfMissing(d,"lanzamientos_porteria","zona_gol","TEXT");
            dropColumnIfPresent(d,"lanzamientos_porteria","tipo");
        }
        public void onCreate(SQLiteDatabase d){}
        public void onUpgrade(SQLiteDatabase d,int o,int n){
            addColumnIfMissing(d,"acciones","zona_gol","TEXT");
            addColumnIfMissing(d,"lanzamientos_porteria","zona_gol","TEXT");
            dropColumnIfPresent(d,"lanzamientos_porteria","tipo");
        }
        void addColumnIfMissing(SQLiteDatabase d,String table,String col,String type){
            try{ d.execSQL("ALTER TABLE "+table+" ADD COLUMN "+col+" "+type); }catch(Exception e){ /* ya existe */ }
        }
        // "Tipo de lanzamiento" ya no se usa (se ha quitado de la app). Si el
        // dispositivo trae una versión moderna de SQLite lo eliminamos de verdad;
        // si no lo soporta, simplemente dejamos de leer/escribir en esa columna.
        void dropColumnIfPresent(SQLiteDatabase d,String table,String col){
            try{ d.execSQL("ALTER TABLE "+table+" DROP COLUMN "+col); }catch(Exception e){ /* SQLite antiguo: se ignora, ya no se usa */ }
        }
        Cursor q(String sql){return getReadableDatabase().rawQuery(sql,null);}
        Cursor q(String sql,String[] args){return getReadableDatabase().rawQuery(sql,args);}

        // ---- Copia de seguridad en la nube: exportar/importar tablas completas como JSON ----
        boolean isEmpty(String[] tables){
            for(String t:tables){
                Cursor c=q("SELECT COUNT(*) FROM "+t);
                boolean hasRows = c.moveToFirst() && c.getInt(0)>0;
                c.close();
                if(hasRows) return false;
            }
            return true;
        }
        JSONArray tableToJson(String table) throws Exception {
            JSONArray arr=new JSONArray();
            Cursor c=q("SELECT * FROM "+table);
            String[] cols=c.getColumnNames();
            while(c.moveToNext()){
                JSONObject row=new JSONObject();
                for(int i=0;i<cols.length;i++){
                    switch(c.getType(i)){
                        case Cursor.FIELD_TYPE_INTEGER: row.put(cols[i], c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(cols[i], c.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_NULL: row.put(cols[i], JSONObject.NULL); break;
                        default: row.put(cols[i], c.getString(i));
                    }
                }
                arr.put(row);
            }
            c.close();
            return arr;
        }
        void wipeTables(String[] tables){
            SQLiteDatabase d=getWritableDatabase();
            for(String t:tables) d.execSQL("DELETE FROM "+t);
            try{ d.execSQL("DELETE FROM sqlite_sequence WHERE name IN ("+
                "'"+String.join("','",tables)+"')"); }catch(Exception ignored){}
        }
        void jsonToTable(String table, JSONArray rows) throws Exception {
            SQLiteDatabase d=getWritableDatabase();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);
                ContentValues v=new ContentValues();
                Iterator<String> keys=row.keys();
                while(keys.hasNext()){
                    String k=keys.next();
                    Object val=row.get(k);
                    if(val==JSONObject.NULL) v.putNull(k);
                    else if(val instanceof Integer) v.put(k,(Integer)val);
                    else if(val instanceof Long) v.put(k,(Long)val);
                    else if(val instanceof Double) v.put(k,(Double)val);
                    else v.put(k, val.toString());
                }
                d.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
        int insertMatch(String e,String r,String f,String comp){ContentValues v=new ContentValues();v.put("equipo",e);v.put("rival",r);v.put("fecha",f);v.put("competicion",comp);return (int)getWritableDatabase().insert("partidos",null,v);}
        void insertPlayer(String n,int d,String p){ContentValues v=new ContentValues();v.put("nombre",n);v.put("dorsal",d);v.put("posicion",p);v.put("activo",1);getWritableDatabase().insert("jugadores",null,v);}
        void deactivatePlayer(int id){ContentValues v=new ContentValues();v.put("activo",0);getWritableDatabase().update("jugadores",v,"id=?",new String[]{""+id});}
        void activatePlayer(int id){ContentValues v=new ContentValues();v.put("activo",1);getWritableDatabase().update("jugadores",v,"id=?",new String[]{""+id});}
        void deletePlayer(int id){
            SQLiteDatabase d=getWritableDatabase();
            d.beginTransaction();
            try{
                d.delete("acciones","jugador_id=?",new String[]{""+id});
                d.delete("estadisticas_jugadores","jugador_id=?",new String[]{""+id});
                d.delete("estadisticas_defensa","jugador_id=?",new String[]{""+id});
                d.delete("lanzamientos_jugadores","jugador_id=?",new String[]{""+id});
                d.delete("jugadores","id=?",new String[]{""+id});
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
        }
        void insertAction(int match,int player,int min,String act,String zone,String res,String zonaGol){
            ContentValues v=new ContentValues();
            v.put("partido_id",match);v.put("jugador_id",player);v.put("minuto",min);v.put("accion",act);v.put("zona",zone);v.put("resultado",res);v.put("observacion","");v.put("zona_gol",zonaGol==null?"":zonaGol);
            getWritableDatabase().insert("acciones",null,v);
        }
        void insertShot(int match,int keeper,String zone,String dir,String res,int min,String zonaGol){
            ContentValues v=new ContentValues();
            v.put("partido_id",match);v.put("portero_id",keeper);v.put("zona",zone);v.put("direccion",dir);v.put("resultado",res);v.put("minuto",min);v.put("observacion","");v.put("zona_gol",zonaGol==null?"":zonaGol);
            getWritableDatabase().insert("lanzamientos_porteria",null,v);
        }
        int ensurePortero(int playerId){Cursor c=q("SELECT nombre,dorsal FROM jugadores WHERE id="+playerId);String n="";int d=0;if(c.moveToFirst()){n=c.getString(0);d=c.getInt(1);}c.close();Cursor x=q("SELECT id FROM porteros WHERE lower(nombre)=lower(?) AND dorsal=? AND activo=1",new String[]{n,""+d});if(x.moveToFirst()){int id=x.getInt(0);x.close();return id;}x.close();ContentValues v=new ContentValues();v.put("nombre",n);v.put("dorsal",d);v.put("activo",1);return (int)getWritableDatabase().insert("porteros",null,v);}
        void deleteAction(int id){getWritableDatabase().delete("acciones","id=?",new String[]{""+id});}
        void deleteShot(int id){getWritableDatabase().delete("lanzamientos_porteria","id=?",new String[]{""+id});}
        void deleteMatch(int id){
            SQLiteDatabase d=getWritableDatabase();d.delete("acciones","partido_id=?",new String[]{""+id});d.delete("lanzamientos_porteria","partido_id=?",new String[]{""+id});d.delete("estadisticas_jugadores","partido_id=?",new String[]{""+id});d.delete("estadisticas_defensa","partido_id=?",new String[]{""+id});d.delete("estadisticas_porteros","partido_id=?",new String[]{""+id});d.delete("lanzamientos_jugadores","partido_id=?",new String[]{""+id});d.delete("partidos","id=?",new String[]{""+id});
        }
        void recalc(int id){
            SQLiteDatabase d=getWritableDatabase();
            Cursor a=d.rawQuery(
                "SELECT COUNT(*) FROM acciones WHERE partido_id=? AND ("+
                "accion='Gol' OR (accion='Lanzamiento' AND resultado='Gol') OR "+
                "accion='7m gol')",
                new String[]{""+id});
            int gf=0;if(a.moveToFirst())gf=a.getInt(0);a.close();
            Cursor b=d.rawQuery("SELECT COUNT(*) FROM lanzamientos_porteria WHERE partido_id=? AND resultado='Gol'",new String[]{""+id});int gc=0;if(b.moveToFirst())gc=b.getInt(0);b.close();
            ContentValues v=new ContentValues();v.put("goles_favor",gf);v.put("goles_contra",gc);d.update("partidos",v,"id=?",new String[]{""+id});
        }
    }
}
