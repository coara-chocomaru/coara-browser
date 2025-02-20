# CoaraBrowser

- Android の WebView を活用した
- 軽量マルチタブ方式の Web ブラウザです。

## 概要

本アプリは、シングルアクティビティで複数の WebView インスタンスを管理し、以下の機能を実現しています。

- **マルチタブブラウジング**  
  複数の WebView を同時に生成・管理し、タブの追加、切替、削除が可能です。

- **ブックマーク & 履歴管理**  
  ユーザーが訪問したページを履歴として保存し、ブックマークの追加、編集、削除、JSON 形式でのエクスポート／インポートに対応しています。

- **ユーザーエージェント切替**  
  CT3UA（特定の Web サービス向け UA）、デスクトップ表示、ガラケー（DoCoMo）UA など、複数のユーザーエージェントを簡単に切替えることができます。

- **ダークモード & ネガポジ変換**  
  Android 10 以降の WebView Force Dark 機能を活用したダークモード、及びページ全体の色反転（ネガポジフィルター）機能を搭載。

- **スクリーンショット撮影**  
  PixelCopy を利用して画面全体のスクリーンショットを撮影し、画像ファイルとして保存します。

## ライセンス
Apache License, Version 2.0
###
使用ライブリ等については下記のNOTICEをご覧ください。
#####
[NOTICE](./NOTICE.md)  
---
Copyright 2025 coara-chocomaru
