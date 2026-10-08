package de.beispiel.brandschutz

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val answers = linkedMapOf<String, String>()
    private var step = 0
    private lateinit var question: TextView
    private lateinit var input: EditText
    private lateinit var choices: LinearLayout
    private lateinit var photo: ImageView
    private var photoFile: File? = null
    private var photoUri: Uri? = null
    private val questions = listOf(
        Q("Schott", "Wie lautet die Schott-Nummer?", Type.TEXT),
        Q("Ebene", "Auf welcher Ebene befindet sich das Schott?", Type.TEXT),
        Q("Raum", "In welchem Raum/Bereich befindet sich die Abschottung?", Type.TEXT),
        Q("Datum", "Welches Datum soll auf dem Formular stehen?", Type.TEXT, SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())),
        Q("Klassifizierung", "Welche Klassifizierung?", Type.CHOICE, listOf("F-30","F-60","F-90","F-120","Rauchschutz")),
        Q("Lage", "Wo befindet sich die Öffnung?", Type.CHOICE, listOf("Wand","Decke")),
        Q("Bauart", "Welche Bauart?", Type.CHOICE, listOf("Massiv","LTW")),
        Q("A", "Maß A in mm", Type.TEXT), Q("B", "Maß B in mm", Type.TEXT), Q("Durchmesser", "Ø in mm", Type.TEXT), Q("T", "T in mm", Type.TEXT),
        Q("Verschluss", "Verschluss bauseitig?", Type.CHOICE, listOf("Ja","Nein")),
        Q("Sonderbauteile", "Sonderbauteile", Type.MULTI, listOf("Vorschott","Rippendecke","BS Unterdecke","Holzbau","Schachtwand","Keine")),
        Q("Zusatz", "Zusatzmaßnahmen", Type.MULTI, listOf("Brandabschnittsverlagerung durch Promatplatten","Brandabschnittsverlagerung durch Mörtel MGIII","Leibung","Schalungsbau","Keine")),
        Q("Durchführung", "Welche Durchführungen sind vorhanden?", Type.MULTI, listOf("nichtbrennbare Rohrleitung","brennbare Rohrleitung","Kabel / Elektroleitungen","BSK / BEK","Keine")),
        Q("System", "Welches Brandschutzsystem wurde verwendet?", Type.CHOICE, listOf("Hilti CFS-B Brandschutzbandage – nicht brennbare Rohre","Hilti CFS-B Brandschutzbandage – brennbare Rohre","Hilti CFS-C Brandschutzmanschette","Hilti CFS-CC Kabelbrandschutzmanschette","Hilti CFS-RCC Kabelbrandschutzmanschette","Hilti CFS-BL P Brandschutzstein Kombi","Hilti CFS-BL P Brandschutzstein quer Kombi","Hilti CFS-PL Brandschutzstopfen","Hilti CFS-F FX-200 Brandschutzschaum","Hilti CFS-F FX-150 Brandschutzschaum","Hilti CP 636 Elektrobrandschutzmörtel","BC Brandchemie Weichschott S-90 UNO","Brandschutzmörtel MGIII","Rockwool Conlit","Abschottung nach MLAR 2015"))
    )

    data class Q(val key:String,val text:String,val type:Type,val options:List<String> = emptyList(),val default:String="")
    enum class Type { TEXT, CHOICE, MULTI }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if(ok) { photo.setImageURI(photoUri); Toast.makeText(this,"Foto übernommen",Toast.LENGTH_SHORT).show() } }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) takePhoto() else Toast.makeText(this,"Kameraberechtigung benötigt",Toast.LENGTH_LONG).show() }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); PDFBoxResourceLoader.init(applicationContext); buildUi() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,32,32,24) }
        val title = TextView(this).apply { text="Brandschutz-Formular"; textSize=26f; gravity=Gravity.CENTER; setPadding(0,0,0,24) }
        root.addView(title)
        question = TextView(this).apply { textSize=21f; setPadding(0,16,0,16) }; root.addView(question)
        input = EditText(this).apply { textSize=18f; setSingleLine(true) }; root.addView(input)
        choices = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(choices)
        photo = ImageView(this).apply { visibility=ImageView.GONE; adjustViewBounds=true; setPadding(0,16,0,16) }; root.addView(photo)
        val buttons = LinearLayout(this).apply { gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL }
        val back=Button(this).apply{text="Zurück"}; val next=Button(this).apply{text="Weiter"}; buttons.addView(back); buttons.addView(next); root.addView(buttons)
        val cam=Button(this).apply{text="📷 Foto aufnehmen / wiederholen"}; root.addView(cam)
        val pdf=Button(this).apply{text="PDF erstellen"}; root.addView(pdf)
        setContentView(root)
        back.setOnClickListener { if(step>0){step--; showStep()} }
        next.setOnClickListener { saveCurrent(); if(step<questions.lastIndex){step++;showStep()}else Toast.makeText(this,"Alle Fragen beantwortet – jetzt PDF erstellen",Toast.LENGTH_LONG).show() }
        cam.setOnClickListener { if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) takePhoto() else cameraPermission.launch(Manifest.permission.CAMERA) }
        pdf.setOnClickListener { saveCurrent(); createPdf() }
        showStep()
    }

    private fun showStep(){ val q=questions[step]; question.text="${step+1}/${questions.size}: ${q.text}"; input.visibility=if(q.type==Type.TEXT)EditText.VISIBLE else EditText.GONE; choices.visibility=if(q.type!=Type.TEXT)LinearLayout.VISIBLE else LinearLayout.GONE; input.setText(answers[q.key]?:q.default); choices.removeAllViews(); if(q.type!=Type.TEXT){q.options.forEach{opt-> val cb=CheckBox(this).apply{text=opt;textSize=17f;isChecked=(answers[q.key]?:"").split("| ").contains(opt)}; choices.addView(cb)}} }
    private fun saveCurrent(){ val q=questions[step]; if(q.type==Type.TEXT) answers[q.key]=input.text.toString() else { val vals=mutableListOf<String>(); for(i in 0 until choices.childCount){ val v=choices.getChildAt(i); if(v is CheckBox && v.isChecked) vals.add(v.text.toString()) }; answers[q.key]=vals.joinToString("| ") } }
    private fun takePhoto(){ photoFile=File.createTempFile("abschottung_",".jpg",cacheDir); photoUri=FileProvider.getUriForFile(this,"$packageName.fileprovider",photoFile!!); photo.visibility=ImageView.VISIBLE; cameraLauncher.launch(photoUri) }

    private fun createPdf(){ try { val template=File(cacheDir,"formular.pdf"); assets.open("formular.pdf").use{it.copyTo(template.outputStream())}; val doc=PDDocument.load(template); val page=doc.getPage(0); val cs=PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true); val font=PDType1Font.HELVETICA; cs.font=font; cs.fontSize=9f
            fun text(s:String,x:Float,y:Float){cs.beginText();cs.newLineAtOffset(x,y);cs.showText(s.take(90));cs.endText()}
            text(answers["Schott"]?:(""),430f,747f); text(answers["Ebene"]?:"",430f,680f); text(answers["Raum"]?:"",430f,628f); text(answers["Datum"]?:"",430f,578f)
            val out=File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"Brandschutz_${System.currentTimeMillis()}.pdf")
            photoFile?.let { if(it.exists()){ val bmp=BitmapFactory.decodeFile(it.absolutePath); val img=LosslessFactory.createFromImage(doc,bmp); page.let{cs.drawImage(img,45f,620f,245f,145f)} } }
            cs.close(); doc.save(out); doc.close(); Toast.makeText(this,"PDF erstellt: ${out.absolutePath}",Toast.LENGTH_LONG).show()
        }catch(e:Exception){Toast.makeText(this,"Fehler: ${e.message}",Toast.LENGTH_LONG).show()} }
}
