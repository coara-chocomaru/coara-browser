package com.coara.browserV2;

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
    private Button button0, buttonDot, buttonLParen, buttonRParen;
    private Button buttonClear, buttonInt, buttonAdd, buttonEqual;
    private Button calcButton;

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
        buttonLParen = findViewById(R.id.buttonLParen);
        buttonRParen = findViewById(R.id.buttonRParen);
        buttonClear = findViewById(R.id.buttonClear);
        buttonInt = findViewById(R.id.buttonInt);
        buttonAdd = findViewById(R.id.buttonAdd);
        buttonEqual = findViewById(R.id.buttonEqual);


        View.OnClickListener appendListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Button b = (Button)v;
                editText.append(b.getText().toString());
            }
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
        buttonLParen.setOnClickListener(appendListener);
        buttonRParen.setOnClickListener(appendListener);
        buttonAdd.setOnClickListener(appendListener);
    
        buttonInt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editText.append("∫(");
            }
        });
    
        buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editText.setText("");
                resultView.setText("");
            }
        });

        buttonEqual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calculate();
            }
        });
    }


    private void calculate() {
        String input = editText.getText().toString().trim();
        try {
        
            if (input.startsWith("∫")) {
                // 例: ∫(0,1){x*x+3}
                int startParen = input.indexOf('(');
                int endParen = input.indexOf(')');
                int startBrace = input.indexOf('{');
                int endBrace = input.lastIndexOf('}');
                if (startParen < 0 || endParen < 0 || startBrace < 0 || endBrace < 0 ||
                        startParen > endParen || startBrace > endBrace) {
                    throw new RuntimeException("積分記法の形式が正しくありません");
                }
                String bounds = input.substring(startParen + 1, endParen);
                String[] parts = bounds.split(",");
                if (parts.length != 2) {
                    throw new RuntimeException("積分の下限と上限が必要です");
                }
                BigDecimal lower = new ExpressionEvaluator().parse(parts[0]);
                BigDecimal upper = new ExpressionEvaluator().parse(parts[1]);
                String integrand = input.substring(startBrace + 1, endBrace);
                
                int n = 1000;
                BigDecimal result = integrate(integrand, lower, upper, n);
                resultView.setText("積分結果: " + result.toPlainString());
            } else {
                // 通常の数式の場合は ExpressionEvaluator
                BigDecimal result = new ExpressionEvaluator().parse(input);
                // 結果の整数性をチェック（余計なゼロを除去）
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
            }
        } catch (Exception e) {
            resultView.setText("計算エラー: " + e.getMessage());
        }
    }

    private BigDecimal integrate(String integrand, BigDecimal lower, BigDecimal upper, int n) {
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal h = (upper.subtract(lower)).divide(new BigDecimal(n), mc);
        BigDecimal sum = evaluateExpression(integrand, lower).add(evaluateExpression(integrand, upper), mc);
        for (int i = 1; i < n; i++) {
            BigDecimal x = lower.add(h.multiply(new BigDecimal(i), mc), mc);
            BigDecimal fx = evaluateExpression(integrand, x);
            if (i % 2 == 0) {
                sum = sum.add(fx.multiply(new BigDecimal("2"), mc), mc);
            } else {
                sum = sum.add(fx.multiply(new BigDecimal("4"), mc), mc);
            }
        }
        BigDecimal result = h.divide(new BigDecimal("3"), mc).multiply(sum, mc);
        return result;
    }

    private BigDecimal evaluateExpression(String expr, BigDecimal x) {
        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        evaluator.setVariable(x);
        return evaluator.parse(expr);
    }


    private class ExpressionEvaluator {
        private String str;
        private int pos = -1;
        private int ch;
        private BigDecimal currentVar = BigDecimal.ZERO; // 変数 x の値

        public void setVariable(BigDecimal x) {
            currentVar = x;
        }

        // 文字列を解析して計算結果を返す
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

        // expression = term { ('+' | '-') term }
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

        // term = factor { ('*' | '/') factor }
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

        // factor = ['+' | '-'] ( number | '(' expression ')' | variable )
        private BigDecimal parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return parseFactor().negate();

            BigDecimal x;
            int startPos = pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) {
                    throw new RuntimeException("括弧が閉じられていません");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                String numStr = str.substring(startPos, pos);
                x = new BigDecimal(numStr, MathContext.DECIMAL128);
            } else if (Character.isLetter(ch)) {
                int start = pos;
                while (Character.isLetter(ch)) nextChar();
                String var = str.substring(start, pos);
                if (var.equalsIgnoreCase("x")) {
                    x = currentVar;
                } else {
                    throw new RuntimeException("未知の識別子: " + var);
                }
            } else {
                throw new RuntimeException("予期しない文字: " + (char) ch);
            }
            return x;
        }
    }
}
