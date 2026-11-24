package uk.zjd.cloudpass.autofilltest

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Форма входа для проверки автозаполнения: что вписалось в поля, видно в строке «result» (только для теста). */
class LoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this).apply { tag = "result"; contentDescription = "result"; text = "result:" }
        val user = EditText(this).apply {
            hint = "Логин"; contentDescription = "login_field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        val pass = EditText(this).apply {
            hint = "Пароль"; contentDescription = "password_field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { result.text = "result:u=${user.text};p=${pass.text}" }
        }
        user.addTextChangedListener(watcher)
        pass.addTextChangedListener(watcher)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(TextView(this@LoginActivity).apply { text = "Autofill Test"; textSize = 22f })
            addView(user)
            addView(pass)
            addView(Button(this@LoginActivity).apply { text = "Войти"; setOnClickListener { finish() } })
            addView(result)
        }
        setContentView(col)
    }
}
