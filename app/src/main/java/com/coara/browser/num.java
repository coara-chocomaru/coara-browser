package com.coara.browser;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

public class num extends AppCompatActivity {

    private EditText editText;
    private TextView resultView;
    private Button button7, button8, button9, buttonDiv;
    private Button button4, button5, button6, buttonMul;
    private Button button1, button2, button3, buttonSub;
    private Button button0, buttonDot, buttonClear, buttonAdd, buttonEqual;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.num);

        editText = findViewById(R.id.editTextNumber);
        resultView = findViewById(R.id.textViewResult);

        button7 = findViewById(R.id.button7);
        button8 = findViewById(R.id.button8);
        button9 = findViewById(R.id.button9);
        buttonDiv = findViewById(R.id.buttonDiv);
        button4 = findViewById(R.id.button4);
        button5 = findViewById(R.id.button5);
        button6 = findViewById(R.id.button6);
        buttonMul = findViewById(R.id.buttonMul);
        button1 = findViewById(R.id.button1);
        button2 = findViewById(R.id.button2);
        button3 = findViewById(R.id.button3);
        buttonSub = findViewById(R.id.buttonSub);
        button0 = findViewById(R.id.button0);
        buttonDot = findViewById(R.id.buttonDot);
        buttonClear = findViewById(R.id.buttonClear);
        buttonAdd = findViewById(R.id.buttonAdd);
        buttonEqual = findViewById(R.id.buttonEqual);

        View.OnClickListener appendListener = v -> {
            Button b = (Button) v;
            editText.append(b.getText().toString());
            editText.requestFocus();
        };

        button7.setOnClickListener(appendListener);
        button8.setOnClickListener(appendListener);
        button9.setOnClickListener(appendListener);
        buttonDiv.setOnClickListener(appendListener);
        button4.setOnClickListener(appendListener);
        button5.setOnClickListener(appendListener);
        button6.setOnClickListener(appendListener);
        buttonMul.setOnClickListener(appendListener);
        button1.setOnClickListener(appendListener);
        button2.setOnClickListener(appendListener);
        button3.setOnClickListener(appendListener);
        buttonSub.setOnClickListener(appendListener);
        button0.setOnClickListener(appendListener);
        buttonDot.setOnClickListener(appendListener);
        buttonAdd.setOnClickListener(appendListener);

        buttonClear.setOnClickListener(v -> {
            editText.setText("");
            resultView.setText("");
            editText.requestFocus();
        });

        buttonEqual.setOnClickListener(v -> {
            calculate();
            editText.requestFocus();
        });
    }

    private void calculate() {
        String input = editText.getText().toString().trim();
        try {
            BigDecimal result = new ExpressionEvaluator().parse(input);
            BigDecimal stripped = result.stripTrailingZeros();
            String output;
            if (stripped.scale() <= 0) {
                BigInteger intResult = stripped.toBigIntegerExact();
                String parity = intResult.mod(BigInteger.valueOf(2)).equals(BigInteger.ONE) ? "奇数" : "偶数";
                output = "結果: " + result.toPlainString() + " (整数 " + parity + ")";
            } else {
                BigInteger intPart = result.toBigInteger();
                String parity = intPart.mod(BigInteger.valueOf(2)).equals(BigInteger.ONE) ? "奇数" : "偶数";
                output = "結果: " + result.toPlainString() + " (小数, 整数部分 " + parity + ")";
            }
            resultView.setText(output);
        } catch (Exception e) {
            resultView.setText("計算エラー: " + e.getMessage());
        }
    }

    private class ExpressionEvaluator {
        private String str;
        private int pos = -1;
        private int ch;

        public BigDecimal parse(String s) {
            this.str = s;
            pos = -1;
            nextChar();
            BigDecimal x = parseExpression();
            if (pos < str.length()) {
                throw new RuntimeException("不正な文字: " + (char) ch);
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

        private BigDecimal parseExpression() {
            BigDecimal x = parseTerm();
            for (;;) {
                if (eat('+')) {
                    x = x.add(parseTerm(), MathContext.DECIMAL128);
                } else if (eat('-')) {
                    x = x.subtract(parseTerm(), MathContext.DECIMAL128);
                } else {
                    return x;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal x = parseFactor();
            for (;;) {
                if (eat('*')) {
                    x = x.multiply(parseFactor(), MathContext.DECIMAL128);
                } else if (eat('/')) {
                    BigDecimal denominator = parseFactor();
                    if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("ゼロ除算");
                    }
                    x = x.divide(denominator, MathContext.DECIMAL128);
                } else {
                    return x;
                }
            }
        }

        private BigDecimal parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return parseFactor().negate();

            BigDecimal x;
            int startPos = pos;
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                String numStr = str.substring(startPos, pos);
                x = new BigDecimal(numStr, MathContext.DECIMAL128);
            } else {
                throw new RuntimeException("予期しない文字: " + (char) ch);
            }
            return x;
        }
    }
}
