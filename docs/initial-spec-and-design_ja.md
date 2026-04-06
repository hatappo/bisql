# SQL テンプレートと自動生成 CRUD 仕様

Clojure 向けの、SQL ファーストかつ安全性をデフォルトとするデータアクセス方式。

---

# 1. 概要

このライブラリは以下を提供する:

- SQL ファーストなクエリ定義（`.sql` ファイルを唯一の信頼できる情報源とする）
- 最小限の 2-way SQL 構文（コメントベースのテンプレート）
- 安全なパラメータバインディング（デフォルトで prepared statement を使用）
- SQL ファイル内に記述する宣言的メタデータ
- データベーススキーマに基づいて自動生成される CRUD 関数
- 初期実装では PostgreSQL のみを対象とする
- 実行バックエンドとしての `next.jdbc`

---

# 2. 設計原則

## 2.1 SQL を唯一の信頼できる情報源とする

すべてのクエリは SQL ファイルで定義する。  
DSL やクエリビルダで SQL を置き換えない。

**理由:**

- SQL はすでに十分に表現力があり、広く理解されている
- クエリを透過的に保ち、レビューしやすくする
- 抽象化の漏れを避ける

---

## 2.2 最小限の構文

サポートするのは、少数のコメントベース構文だけとする。

**理由:**

- 完全なテンプレート言語を避ける
- SQL の可読性を維持する
- 認知負荷を下げる

---

## 2.3 デフォルトで安全

- デフォルトでは常に bind variable を使う
- 危険な操作は明示的な構文が必要

**理由:**

- SQL インジェクションを防ぐ
- 予測しやすい実行計画を促す
- 意図しないフルスキャンを避ける

---

## 2.4 インデックスを意識したアクセス

生成されるクエリは、データベースのインデックス構造に従う。

**理由:**

- 効率的なクエリを促す
- アプリケーションコードを DB 設計に揃える
- 非効率なアクセスパターンを防ぐ

---

## 2.5 薄い統合レイヤ

このライブラリは JDBC アクセス層そのものを置き換えることを目的としない。

初期実装では、実行バックエンドとして `next.jdbc` を前提とする。

**理由:**

- Clojure エコシステムにおける事実上の標準的な JDBC レイヤを再利用できる
- このライブラリの責務を SQL の読み込み、展開、CRUD 生成に集中できる
- 低レイヤの DB 処理を重複実装せずに済む

---

# 3. SQL ファイル配置

## 3.1 推奨パス規約

SQL ファイルは `resources/sql/` 配下に置く。

推奨レイアウト:

```text
resources/sql/<database>/<schema>/<table>/<function-name>.sql
```

例:

```text
resources/sql/postgresql/public/users/get-by-id.sql
```

### ルール

- 初期実装では `postgresql` をデフォルトの database セグメントとする
- デフォルトスキーマは `public`
- サブディレクトリは namespace 的なグルーピングに対応する
- ファイル名が関数名を決める

**理由:**

- SQL の整理をデータベース構造に揃えられる
- 自動生成クエリと手書きクエリを見つけやすい
- 別の命名レジストリを持たずに済む

---

# 4. SQL テンプレート構文

## 4.1 Bind Variables — `/*$name*/`

### 説明

Prepared statement のパラメータ。

- `?` に置き換えられる
- 値自体は別で渡される

### 例

```sql
SELECT * FROM users WHERE id = /*$id*/1
```

→

```sql
SELECT * FROM users WHERE id = ?
```

bind 値:

```clojure
[123]
```

---

## 4.2 コレクションのバインディング (`IN`)

```sql
WHERE id IN /*$ids*/(1,2,3)
```

```clojure
{:ids [10 20 30]}
```

→

```sql
WHERE id IN (?, ?, ?)
```

bind 値:

```clojure
[10 20 30]
```

### 制約

- `IN (...)` の中でのみ有効
- 空コレクションは許可しない

**理由:**

- 無効な SQL の生成を避ける
- 意味が曖昧になるのを防ぐ

---

## 4.3 条件ブロック — `/*%if*/`

```sql
WHERE 1 = 1
/*%if name */
  AND name = /*$name*/'foo'
/*%end */
```

### サポートする形式

- `x`

### 評価ルール

- 真偽判定は Clojure の semantics に従う
- `nil` と `false` だけを偽として扱う
- 未指定のパラメータは `nil` として扱う
- 空文字列や空コレクションは真として扱う

### 初期実装での制約

- サポートするのは単一の変数名だけ
- 式構文は初期実装では意図的にサポートしない

---

## 4.4 LIKE の扱い

LIKE は型付きの値で扱う。

```sql
WHERE name LIKE /*$name*/'foo%'
```

```clojure
{:name (sql/like-prefix "smith")}
```

→

```sql
WHERE name LIKE ?
```

bind 値:

```clojure
["smith%"]
```

---

## 4.5 リテラル変数 — `/*^name*/` (任意)

SQL リテラルを直接埋め込む。

```sql
WHERE type = /*^type*/'A'
```

→

```sql
WHERE type = 'BOOK'
```

### 初期実装でのルール

- 対応データベースは PostgreSQL のみ
- `String` は単一引用符付きの SQL 文字列リテラルとして埋め込む
- `String` に `'` を含めてはならない
- 数値はクオートせず、そのままリテラルとして埋め込む
- 未対応の型はエラーにする

**理由:**

- まれな SQL ケースでは必要になる
- 安全性の懸念があるためデフォルトにはしない
- 初期実装では PostgreSQL 固有の振る舞いを許容する
- 受け付ける型を絞ることで曖昧なレンダリング規則を避ける

---

## 4.6 埋め込み変数 — `/*!name*/` (上級者向け)

明示的なエスケープハッチとして、生の SQL 断片を注入する。

```sql
ORDER BY /*!order-by*/id DESC
```

### 方針

- 安全性は意図的に、それを使う開発者に委ねる
- `/*!name*/` はデフォルトで安全な機能ではない
- 生 SQL の注入が本当に必要な場合を除き、`/*$name*/` または `/*^name*/` を優先する

**理由:**

- それ以外では表現しづらい SQL 構文がある
- 危険な振る舞いは明示的かつ opt-in のままにすべき

---

# 5. 宣言コメント

宣言コメントはメタデータとドキュメントを提供する。

## 5.1 構文

```sql
/*:doc
...
*/

/*:meta
{...}
*/
```

### ルール

- ファイル先頭でのみ認識する
- SQL 本文より前に置かなければならない
- それ以降に出現しても無視する

---

## 5.2 `/*:doc */`

関数の docstring を定義する。

```sql
/*:doc
Find orders by customer ID.
*/
```

---

## 5.3 `/*:meta */`

メタデータ（EDN）を定義する。

```sql
/*:meta
{:tags [:orders :list]
 :since "0.1.0"}
*/
```

---

## 5.4 命名

- 関数名は SQL ファイル名から導出する
- `/*:name */` ディレクティブは使わない

**理由:**

- 信頼できる情報源の重複を避ける
- 不整合を防ぐ
- ファイル構成をシンプルに保つ

---

# 6. 公開 API の最小形

初期実装では、公開 API は小さく保つ。

## 6.1 読み込み

```clojure
(load-query "postgresql/public/users/get-by-id.sql")
```

`resources/sql/...` から SQL ファイルを読み込み、パース済み表現を返す。

## 6.2 レンダリング

```clojure
(render-query query {:id 1})
```

テンプレート SQL を次の形に展開する:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [1]}
```

## 6.3 実行

```clojure
(execute-one! datasource query params)
(execute! datasource query params)
```

これらの関数は実行を `next.jdbc` に委譲する。

## 6.4 CRUD 生成

```clojure
(generate-crud datasource {:schema "public"})
```

PostgreSQL のスキーマメタデータから、クエリ定義と呼び出し可能な関数を生成する。

**理由:**

- API の表面積を小さく保てる
- 読み込み、展開、実行、生成の責務を分離できる
- 各レイヤを独立にテストしやすい

---

# 7. 自動生成 CRUD 関数

## 7.1 概要

CRUD 関数はデータベーススキーマから生成する:

- 主キー
- unique 制約
- インデックス

### 対象範囲

- 対応データベースは PostgreSQL のみ
- デフォルトスキーマは `public`
- スキーマは明示指定も可能
- partial index は初期実装では対象外
- expression index は初期実装では対象外

---

## 7.2 Insert

```clojure
(insert! db row)
```

---

## 7.3 Update

以下の場合にのみ生成する:

- 主キー
- unique 制約（完全一致）

```clojure
(update-by-id! db {:id ...
                   :set {...}})
```

複合キーの例:

```clojure
(update-by-account-id-and-user-id! db {:account-id ...
                                       :user-id ...
                                       :set {...}})
```

---

## 7.4 Delete

以下の場合にのみ生成する:

- 主キー
- unique 制約（完全一致）

```clojure
(delete-by-id! db {:id ...})
```

複合キーの例:

```clojure
(delete-by-account-id-and-user-id! db {:account-id ...
                                       :user-id ...})
```

---

## 7.5 Select

### 7.5.1 `get-by-*`

以下の場合に生成する:

- 主キー（完全一致）
- unique 制約（完全一致）

```clojure
(get-by-id db {:id ...})
```

複合キーの例:

```clojure
(get-by-account-id-and-user-id db {:account-id ...
                                   :user-id ...})
```

---

### 7.5.2 `list-by-*`

以下に対して生成する:

- すべてのインデックス左プレフィックスパターン

---

## 7.6 インデックス左プレフィックス規則

インデックス `(a, b, c)` に対して:

生成されるプレフィックス:

- `(a)`
- `(a, b)`
- `(a, b, c)`

生成しないもの:

- `(b)`
- `(b, c)`
- `(a, c)`

**理由:**

- 左プレフィックスだけがインデックスを効率的に使える
- 誤解を招く、または非効率なクエリを避ける

---

## 7.7 ORDER BY（自然なインデックス順）

残りのインデックス列を `ORDER BY` に利用する。

例:

```
(customer_id, created_at, id)
```

### prefix: `customer_id`

```sql
WHERE customer_id = ?
ORDER BY created_at, id
```

### prefix: `customer_id, created_at`

```sql
WHERE customer_id = ?
  AND created_at = ?
ORDER BY id
```

### 完全一致

```sql
WHERE customer_id = ?
  AND created_at = ?
  AND id = ?
```

(`ORDER BY` なし)

---

### 制約

- すべての列は昇順のみ
- 初期実装では DESC はサポートしない

**理由:**

- 実装を単純に保つ
- 実運用の大半のケースをカバーできる

---

## 7.8 LIMIT（必須）

すべての `list-by-*` 関数は `limit` を必須とする。

```clojure
(list-by-customer-id db {:customer-id 10
                         :limit 100})
```

### ルール

- 正の整数でなければならない
- 初期実装では無制限オプションを持たない

**理由:**

- 意図しないフルスキャンを防ぐ
- 結果件数への意識を強制する
- 効率的なアクセスパターンを促す

---

## 7.9 命名

生成される名前:

```clojure
list-by-customer-id-and-created-at
```

ルール:

- 列名に基づく
- kebab-case に変換する
- `and` で連結する
- 複合主キーおよび複合 unique 制約も同じ規則に従う
- 生成関数名には constraint 名を使わない

**理由:**

- 明示的で読みやすい
- 曖昧さがない
- DB スキーマとの対応が追いやすい

---

## 7.10 生成しないもの

以下は意図的に除外する:

- count 関数（将来対応予定）
- 範囲クエリ（`>=`, `<=`）
- OR 条件
- JOIN クエリ
- 集約クエリ
- 動的な並び替え
- partial index
- expression index

**理由:**

- API の爆発を避ける
- 自動生成関数を予測しやすく保つ
- 複雑なクエリでは明示的な SQL を促す

---

# 8. テスト戦略

テストはレイヤごとに分離する:

- parser 単体テスト
- SQL レンダリングテスト
- PostgreSQL 統合テスト

**理由:**

- parser の振る舞いを JDBC から独立して検証できる
- レンダリングの振る舞いを DB 状態から独立して検証できる
- 統合テストで PostgreSQL 実行とスキーマ introspection を検証できる

---

# 9. まとめ

このシステムは以下を提供する:

- SQL ファーストな設計
- 最小限のテンプレート構文
- 安全なパラメータ処理
- インデックスを意識したクエリ生成
- 予測可能で制約の明確な CRUD API

---

# 10. 今後の拡張

- count 関数
- 範囲ベースクエリ
- カーソルベースページネーション
- より豊かなメタデータ注釈
- スキーマバリデーション連携（Malli）
