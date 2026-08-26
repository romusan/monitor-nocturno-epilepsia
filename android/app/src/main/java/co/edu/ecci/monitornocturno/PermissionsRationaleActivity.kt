package co.edu.ecci.monitornocturno

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Uso de datos de salud"
        setContentView(TextView(this).apply {
            text = "Monitor nocturno ECCI solicita acceso de solo lectura a la frecuencia cardiaca y la saturacion de oxigeno almacenadas en Health Connect. Los datos se muestran localmente para apoyar el registro nocturno y no sustituyen la valoracion ni el protocolo medico."
            textSize = 18f
            setPadding(48, 48, 48, 48)
        })
    }
}
