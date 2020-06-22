package it.tonight.gmic_android_example

import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.app.Activity
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import org.jetbrains.anko.doAsync
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.view.*


class MainActivity : Activity() {
    private val REQUEST_OPEN_IMAGE = 1001
    private val REQUEST_SAVE_IMAGE = 1002

    fun gmic(input: Bitmap, command: String): Bitmap {
        // I put update250.gmic on cache dir to make it available to executable
        Log.d("GMIC", "Loading stdlib...")
        val tmpPath = cacheDir.resolve("gmic/update250.gmic")
        if (!tmpPath.exists()) {
            cacheDir.resolve("gmic").mkdirs()
            assets.open("gmic/update250.gmic").use { inStream ->
                tmpPath.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
        }

        // Gmic is not compiled with jpg, png or other library
        // It can only read ppm, bmp.
        val inputFile = cacheDir.resolve("tmp_input.ppm")
        val outputFile = cacheDir.resolve("tmp_output.bmp")

        // Remove spurious files
        if (inputFile.exists()) inputFile.delete()
        if (outputFile.exists()) outputFile.delete()

        // Create a ppm from bitmap.
        Log.d("GMIC", "Creating input file...")
        val arr = IntArray(input.width * input.height)
        input.getPixels(arr, 0, input.width, 0, 0, input.width, input.height);

        val out = BufferedOutputStream(FileOutputStream(inputFile))
        out.write(("P6 \n" + input.width.toString() + " " + input.height.toString() + " \n255 \n").toByteArray())
        for (i in 0 until arr.size) {
            val r = ((arr[i] ushr 16) and 0xff).toByte()
            val g = ((arr[i] ushr 8) and 0xff).toByte()
            val b = ((arr[i] ushr 0) and 0xff).toByte()

            out.write(byteArrayOf(r, g, b))
        }

        out.flush()
        out.close()

        // Command to send
        Log.d("GMIC", "Apply Effect...")
        val path =
            applicationContext.getApplicationInfo().nativeLibraryDir + "/gmic.so " + inputFile.absoluteFile + " " + command + " -output " + outputFile.absoluteFile

        // Exec g'mic and capture stderr to log errors and status
        val process = Runtime.getRuntime().exec(path, arrayOf("GMIC_PATH=" + applicationContext.cacheDir))
        val reader = BufferedReader(InputStreamReader(process.errorStream))

        val buffer = CharArray(4096)
        val output = StringBuffer()

        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) break
            output.append(buffer, 0, read);
        }

        reader.close()
        process.waitFor()
        println(output)

        // Read bitmap from output file
        Log.d("GMIC", "Done")
        val result = BitmapFactory.decodeFile(outputFile.absolutePath)

        // Delete temporary files
        if (inputFile.exists()) inputFile.delete()
        if (outputFile.exists()) outputFile.delete()

        return result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var path = String
        findViewById<TextView>(R.id.editText2).visibility = View.GONE
        val image = findViewById<ImageView>(R.id.image)
        val openButton = findViewById<Button>(R.id.button2)
        val saveButton = findViewById<Button>(R.id.button3)
        val button = findViewById<Button>(R.id.button)

        openButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_OPEN_IMAGE)
        }

        saveButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_TITLE, "output.png")
            startActivityForResult(intent, REQUEST_SAVE_IMAGE)
        }

        button.setOnClickListener {

            // Show a progress dialog
            val progressDialog = ProgressDialog(this)
            progressDialog.isIndeterminate = true
            progressDialog.setMessage("Applying effect...\nArg:\n"+findViewById<TextView>(R.id.editText).text.toString().replace("\\\n\t"," ").replace("\\\n "," ").replace("\n",""))
            progressDialog.setCancelable(false)
            progressDialog.show()

            // Disable button during processing
            button.isEnabled = false


            doAsync {
                if (findViewById<ImageView>(R.id.image).drawable is BitmapDrawable) {
                    val exampleBitmap = (findViewById<ImageView>(R.id.image).drawable as BitmapDrawable).bitmap
                    var exception: java.lang.Exception? = null
                    var outputBitmap: Bitmap? = null

                    try {
                        // Apply bokeh effect!
                        outputBitmap = gmic(
                            exampleBitmap,
                            findViewById<TextView>(R.id.editText).text.toString().replace(
                                "\\\n\t",
                                " "
                            ).replace("\\\n ", " ").replace("\n", " ")
                        )
                    } catch (e: java.lang.Exception) {
                        exception = e
                    }

                    // Finished. Hide progress dialog.
                    runOnUiThread {
                        progressDialog.dismiss()
                        if (exception == null && outputBitmap != null) {
                            image.setImageBitmap(outputBitmap)
                        } else {
                            if (exception != null) {
                                Toast.makeText(applicationContext, "ERROR: outputBitmap is null", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(applicationContext, "ERROR: Failed${Log.getStackTraceString(exception)}", Toast.LENGTH_LONG).show()
                            }
                        }
                        //exampleBitmap.recycle()
                        button.isEnabled = true
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "ERROR: Image isn't BitmapDrawable", Toast.LENGTH_LONG)
                            .show()
                        progressDialog.dismiss()
                        button.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && data?.data != null) when (requestCode) {
            REQUEST_OPEN_IMAGE -> {
                var pfDescriptor: ParcelFileDescriptor? = null
                try {
                    val uri = data.data
                    pfDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    if (pfDescriptor != null) {
                        val fileDescriptor = pfDescriptor.fileDescriptor
                        val bmp = BitmapFactory.decodeFileDescriptor(fileDescriptor)
                        pfDescriptor.close()
                        findViewById<ImageView>(R.id.image).setImageBitmap(bmp)
                    }
                } catch (e: java.io.IOException) {
                    e.printStackTrace()
                } finally {
                    try {
                        pfDescriptor?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                }
            }

            REQUEST_SAVE_IMAGE -> {
                try {
                    contentResolver.openOutputStream(data.data).use { outputStream ->
                        (findViewById<ImageView>(R.id.image).drawable as BitmapDrawable).bitmap.compress(
                            Bitmap.CompressFormat.PNG,
                            100,
                            outputStream
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }
        }
    }
}