package com.coara.browser;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.math.BigInteger;

public class num extends AppCompatActivity {

    private EditText editText;
    private TextView resultView;
    private Button button0, button1, button2, button3, button4, button5, button6, button7, button8, button9;
    private Button buttonAdd, buttonSub, buttonMul, buttonDiv, buttonClear, buttonEqual;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.num);

        editText = findViewById(R.id.editTextNumber);
        resultView = findViewById(R.id.textViewResult);

        button0 = findViewById(R.id.button0);
        button1 = findViewById(R.id.button1);
        button2 = findViewById(R.id.button2);
        button3 = findViewById(R.id.button3);
        button4 = findViewById(R.id.button4);
        button5 = findViewById(R.id.button5);
        button6 = findViewById(R.id.button6);
        button7 = findViewById(R.id.button7);
        button8 = findViewById(R.id.button8);
        button9 = findViewById(R.id.button9);
        buttonAdd = findViewById(R.id.buttonAdd);
        buttonSub = findViewById(R.id.buttonSub);
        buttonMul = findViewById(R.id.buttonMul);
        buttonDiv = findViewById(R.id.buttonDiv);
        buttonClear = findViewById(R.id.buttonClear);
        buttonEqual = findViewById(R.id.buttonEqual);

        View.OnClickListener appendListener = v -> {
            editText.append(((Button) v).getText().toString());
        };

        button0.setOnClickListener(appendListener);
        button1.setOnClickListener(appendListener);
        button2.setOnClickListener(appendListener);
        button3.setOnClickListener(appendListener);
        button4.setOnClickListener(appendListener);
        button5.setOnClickListener(appendListener);
        button6.setOnClickListener(appendListener);
        button7.setOnClickListener(appendListener);
        button8.setOnClickListener(appendListener);
        button9.setOnClickListener(appendListener);
        buttonAdd.setOnClickListener(appendListener);
        buttonSub.setOnClickListener(appendListener);
        buttonMul.setOnClickListener(appendListener);
        buttonDiv.setOnClickListener(appendListener);

        buttonClear.setOnClickListener(v -> {
            editText.setText("");
            resultView.setText("");
        });

        buttonEqual.setOnClickListener(v -> calculate());
    }

    private void calculate() {
        String input = editText.getText().toString().trim();
        if (input.isEmpty()) {
            resultView.setText("");
            return;
        }
        try {
            BigInteger result = new ExpressionEvaluator().parse(input);
            resultView.setText("結果: " + result.toString());
        } catch (ArithmeticException ae) {
            resultView.setText("ゼロ除算エラー");
        } catch (Exception e) {
            resultView.setText("計算エラー");
        }
    }

    private class ExpressionEvaluator {
        private String str;
        private int pos = -1;
        private int ch;

        public BigInteger parse(String s) {
            this.str = s;
            pos = -1;
            nextChar();
            BigInteger x = parseExpression();
            if (pos < str.length()) {
                throw new RuntimeException("不正な文字");
            }
            return x;
        }

        private void nextChar() {
            pos++;
            ch = pos < str.length() ? str.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private BigInteger parseExpression() {
            BigInteger x = parseTerm();
            while (true) {
                if (eat('+')) {
                    x = x.add(parseTerm());
                } else if (eat('-')) {
                    x = x.subtract(parseTerm());
                } else {
                    return x;
                }
            }
        }

        private BigInteger parseTerm() {
            BigInteger x = parseFactor();
            while (true) {
                if (eat('*')) {
                    x = x.multiply(parseFactor());
                } else if (eat('/')) {
                    BigInteger divisor = parseFactor();
                    if (divisor.equals(BigInteger.ZERO)) {
                        throw new ArithmeticException("ゼロ除算");
                    }
                    x = x.divide(divisor);
                } else {
                    return x;
                }
            }
        }

        private BigInteger parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return parseFactor().negate();

            int startPos = pos;
            if (ch >= '0' && ch <= '9') {
                while (ch >= '0' && ch <= '9') nextChar();
                String numStr = str.substring(startPos, pos);
                return new BigInteger(numStr);
            }
            throw new RuntimeException("予期しない文字");
        }
    }
}
