class MainActivity : AppCompatActivity() {
    private lateinit var translator: OnDeviceTranslator
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        translator = OnDeviceTranslator(this)
        
        findViewById<Button>(R.id.btnTranslate).setOnClickListener {
            val text = findViewById<EditText>(R.id.etInput).text.toString()
            
            lifecycleScope.launch {
                val result = translator.translate(text)
                findViewById<TextView>(R.id.tvResult).text = result
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        translator.close()
    }
}
