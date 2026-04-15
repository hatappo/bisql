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

SQL ファイルは classpath 上の論理パス `sql/` 配下に置く。
通常は `src/sql/` または `resources/sql/` に置く。

推奨レイアウト:

```text
sql/<database>/<schema>/<table>/<function-name>.sql
```

例:

```text
src/sql/postgresql/public/users/get-by-id.sql
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
- パラメータ名には `user.profile.status` のような多段 dot-path を使える
- 各 path segment の lookup 順は `keyword` → `string` → `symbol`

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

### `DEFAULT`

scalar の bind 変数に `bisql/DEFAULT` を渡した場合、`?` ではなく SQL の `DEFAULT` として出力される。

```clojure
{:status bisql/DEFAULT}
```

```sql
INSERT INTO users (status) VALUES (/*$status*/'active')
```

→

```sql
INSERT INTO users (status) VALUES (DEFAULT)
```

### `ALL`

scalar の bind 変数に `bisql/ALL` を渡した場合、`?` ではなく SQL の `ALL`
として出力される。

```clojure
{:limit bisql/ALL}
```

例:

```sql
SELECT * FROM users LIMIT ALL
```

初期実装での注意:

- scalar の `$` バインディングでのみサポートする
- `IN /*$ids*/(...)` のような collection binding では使えない

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
/*%else */
  AND status = 'inactive'
/*%end */
```

### サポートする形式

- `x`
- `if / elseif / else / end`
- inline `elseif` 断片: `/*%elseif condition => <fragment> */`
- inline `else` 断片: `/*%else => <fragment> */`

### 評価ルール

- 真偽判定は Clojure の semantics に従う
- `nil` と `false` だけを偽として扱う
- 未指定のパラメータは `nil` として扱う
- 空文字列や空コレクションは真として扱う
- `elseif` は、最初の `if` に続く truthy な branch のうち最初に一致したものを使う
- `elseif` が `=> <fragment>` を持つ場合、その inline fragment を elseif body として使う
- `else` が `=> <fragment>` を持つ場合、その inline fragment を else body として使う
- `inline elseif => <fragment>` または `inline else => <fragment>` と comment 外 body を同時に持つ場合、そのテンプレートは不正として拒否する
- `else` の後ろに `elseif` は置けない
- falsy と評価された条件ブロックの直後に `AND` または `OR` がある場合、その後続の演算子も取り除く
- falsy と評価された条件ブロックの直後に `AND` または `OR` がなく、直前に `WHERE` または `HAVING` がある場合、その節キーワードも取り除く

### 初期実装での制約

- `if` と `elseif` でサポートするのは単一の変数名だけ
- 式構文は初期実装では意図的にサポートしない
- `else` は式を取らない

### `/*%for*/`

```sql
UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/column_name = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1
```

### ルール

- 構文は `/*%for item in items */ ... /*%end */`
- 区切りが必要な場合は `/*%for item in items separating , */ ... /*%end */` の構文も使える
- `item` はループ内でのみ有効なローカル変数名
- `item.name`, `item.value`, `user.profile.name` のような dot-path 参照をサポートする
- dot-path の key lookup は `keyword` → `string` → `symbol` の順で行う
- 空コレクションはエラーにしない
- 空の `for` ブロックの直後に `AND` または `OR` がある場合、その後続の演算子も取り除く
- 空の `for` ブロックの直後に `AND` または `OR` がなく、直前に `WHERE` または `HAVING` がある場合、その節キーワードも取り除く
- `separating` を使った場合、区切り文字は 2 件目以降の反復の前に出力される
- 繰り返し内容の末尾に `,`, `AND`, `OR` を置いて最後だけ削る仕様は使わない。区切りが必要なら `separating` を使う

### 初期実装での制約

- 初期実装では nested `for` をサポートしない

---

## 4.4 想定ユースケース

`if` と `for` は、SQL ファイル全体を汎用プログラミング言語化するためではなく、SQL の一部を局所的に組み立てるために使う。

### `if` の主な想定ユースケース

#### `WHERE` / `HAVING` の条件出し分け

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%end */
```

#### `SET` 項目の出し分け

```sql
UPDATE users
SET
/*%if display-name */
  display_name = /*$display-name*/'Alice'
/*%else */
  display_name = display_name
/*%end */
WHERE id = /*$id*/1
```

#### inline `else` 断片

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%else => status = 'inactive' */
/*%end */
```

#### `elseif` 分岐

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%elseif pending */
  status = 'pending'
/*%else */
  status = 'inactive'
/*%end */
```

#### inline `elseif` 断片

```sql
SELECT *
FROM users
WHERE
/*%if active */
  active = true
/*%elseif pending => status = 'pending' */
/*%else => status = 'inactive' */
/*%end */
```

#### `ORDER BY` / `LIMIT` の有無の切り替え

```sql
SELECT *
FROM users
/*%if sort-by-created-at */
ORDER BY created_at DESC
/*%end */
/*%if limit */
LIMIT /*$limit*/100
/*%end */
```

### `for` の主な想定ユースケース

#### `WHERE` 句の条件列挙

```sql
SELECT *
FROM users
WHERE
/*%for item in filters separating AND */
  /*!item.column*/column_name = /*$item.value*/'sample'
/*%end */
```

#### `UPDATE ... SET` の項目列挙

```sql
UPDATE users
SET
/*%for item in items separating , */
  /*!item.name*/column_name = /*$item.value*/'sample'
/*%end */
WHERE id = /*$id*/1
```

#### `INSERT` の列と値の列挙

```sql
INSERT INTO users (
/*%for column in columns separating , */
  /*!column.name*/column_name
/*%end */
) VALUES (
/*%for column in columns separating , */
  /*$column.value*/'sample'
/*%end */
)
```

#### 複数行 `VALUES`

```sql
INSERT INTO users (email, status)
VALUES
/*%for row in rows separating , */
  (/*$row.email*/'user@example.com', /*$row.status*/'active')
/*%end */
```

---

## 4.5 LIKE の扱い

LIKE は型付きの値で扱う。

```sql
WHERE name LIKE /*$name*/'foo%'
```

```clojure
{:name (bisql/LIKE_STARTS_WITH "smith")}
```

→

```sql
WHERE name LIKE ?
```

bind 値:

```clojure
["smith%"]

最初の公開ヘルパーは次の 3 つ:

- `bisql/LIKE_STARTS_WITH`
- `bisql/LIKE_ENDS_WITH`
- `bisql/LIKE_CONTAINS`

escape 文字は `\` 固定とする。
helper は次を escape する:

- `%`
- `_`
- `\`
```

---

## 4.6 リテラル変数 — `/*^name*/` (任意)

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

## 4.7 埋め込み変数 — `/*!name*/` (上級者向け)

明示的なエスケープハッチとして、生の SQL 断片を注入する。

```sql
ORDER BY /*!order-by*/column_name DESC
```

### 方針

- `/*!name*/` も `/*$name*/` や `/*^name*/` と同様に、直後にサンプルトークンが必要
- 安全性は意図的に、それを使う開発者に委ねる
- `/*!name*/` はデフォルトで安全な機能ではない
- 生 SQL の注入が本当に必要な場合を除き、`/*$name*/` または `/*^name*/` を優先する

**理由:**

- それ以外では表現しづらい SQL 構文がある
- 危険な振る舞いは明示的かつ opt-in のままにすべき

---

# 5. 宣言コメント

宣言コメントは template metadata を提供する。

## 5.1 構文

```sql
/*:<name>
<edn>
*/
```

### ルール

- declaration 名は任意
- 宣言コメントは template block の先頭でのみ認識する
- 同じ declaration の重複はエラー
- declaration body はデフォルトでは EDN としてパースする
- `/*:doc */` だけは、EDN として読めない場合に trim した plain string として扱う
- 複数 template を含むファイルでは、それぞれの template block が自身の declaration を持つ
- パース結果は `:meta` 配下に返す

例:

```sql
/*:doc
Find orders by customer ID.
*/

/*:tags
[:orders :list]
*/
```

→

```clojure
{:meta {:doc "Find orders by customer ID."
        :tags [:orders :list]}}
```

---

## 5.2 `/*:name */`

template 内ローカルな query 名を定義する。

```sql
/*:name find-user-by-email */
SELECT * FROM users WHERE email = /*$email*/'user@example.com'
```

### 命名ルール

- 1 template だけを含むファイルでは `/*:name */` を省略できる
- 複数 template を含むファイルでは各 template に `/*:name */` が必須
- `load-query` は単一 template ファイルのみを扱う
- `load-queries` は `query-name` をキーにして template を返す
- `:name` は `query-name` の解決に使い、返却される `:meta` にも残す
- SQL ファイル名（`.sql` を除く）や `/*:name */` に含まれる `.` は namespace 区切りとして扱う
- 最後のセグメントが関数名になる
- それ以前のセグメントは、SQL ファイルの親ディレクトリ由来 namespace の下に付け足す suffix になる
- namespace suffix が与えられなかった場合、デフォルトでは `core` を使う

---

# 6. 公開 API の最小形

初期実装では、公開 API は小さく保つ。

## 6.1 読み込み

```clojure
(load-query "postgresql/public/users/get-by-id.sql")
```

classpath 上の `sql/...` から SQL ファイルを読み込み、パース済み表現を返す。

## 6.2 レンダリング

```clojure
(render-query query {:id 1})
```

テンプレート SQL を次の形に展開する:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :params [1]}
```

コンパイラ実装の土台として、次も公開する:

```clojure
(def parsed-template
  (parse-template "SELECT * FROM users WHERE id = /*$id*/1"))
(renderer-plan parsed-template)
(emit-renderer-form parsed-template)
(compile-renderer parsed-template)
(evaluate-renderer parsed-template {:id 1})
```

`parse-template` は declaration を除いた SQL template 文字列を parsed-template
へ変換する。`renderer-plan` はその parsed-template を実行寄りの plan へ変換する。
`emit-renderer-form` はその plan から再利用可能な renderer 関数 form を生成する。
`compile-renderer` は parsed-template を実行時に再利用可能な renderer 関数へ
コンパイルする。`evaluate-renderer` は parsed-template を直接評価し、内部
renderer 段階と同じ形を返す:

```clojure
{:sql "SELECT * FROM users WHERE id = ?"
 :bind-params [1]}
```

parsed-template 層は parser の出力であり、statement kind (`:select`,
`:insert`, `:update`, `:delete`) と、`:where`, `:having`, `:set`, `:values`,
`:limit`, `:offset` のような clause 単位の文脈も node に注釈する。
renderer-plan 層は、その後段の code generation と interpreter ベースの
renderer 経路のための実行寄り中間表現である。

現在の `renderer-plan` は、次の形を安定契約として持つ:

```clojure
{:op :renderer-plan
 :statement-kind :select
 :steps [...]}
```

top-level の `:steps` ベクタは、実行寄りの step map を並べたものになる。
現在の step 種別は次の4つである:

- `:append-text`
- `:append-variable`
- `:branch`
- `:for-each`

`:append-text` は `:sql`, `:context`, `:statement-kind` を持つ。
`:append-variable` は `:sigil`, `:parameter-name`, `:collection?`, `:context`,
`:statement-kind` を持つ。

`:branch` は `:branches` を持ち、各 branch は次の形になる:

```clojure
{:expr "active" ;; else は nil
 :steps [...]}
```

`:for-each` は loop 契約として次の形を持つ:

```clojure
{:op :for-each
 :item-name "item"
 :collection-name "items"
 :separator ","
 :context :set
 :statement-kind :update
 :steps [...]}
```

emitted Clojure form の正確な形自体は implementation detail だが、
`parsed-template -> renderer-plan -> renderer-form` という層分離は、
今後の compiler 境界として固定していく。

CLJ では、`compile-renderer` は引き続き emitted renderer form を `eval` して
関数化する。CLJS では、同じ `renderer-plan` を小さな interpreter へ入力し、
その interpreter を背後に持つ再利用可能 renderer 関数を返す。

現在の主経路は `emit-renderer-form` である。`defrender` と `defquery` は、
マクロ展開時に emitted renderer form をそのまま埋め込み、
`compile-renderer` は `eval` を使う実行時向けの薄い convenience wrapper
として残している。

## 6.3 関数定義

```clojure
(defrender)
(defrender "admin")
(defrender "/sql/postgresql/public/users/get-by-id.sql")
(defrender "/sql/postgresql/public/users")
```

`defrender` は、SQL ファイルに含まれる query ごとにレンダリング関数を定義する。
引数なしの場合は、現在の namespace を classpath 上のディレクトリへ変換し、
その配下の `.sql` ファイルを再帰的に走査して定義する。相対パスを渡した場合は、
その namespace 由来ディレクトリ配下として解決する。`/` から始まるパスを渡した
場合は classpath root からの絶対パスとして解決する。ファイルではなくディレクトリを
渡した場合は、その配下の `.sql` ファイルを再帰的に走査し、見つかった query を
すべて定義する。現在の namespace は探索起点に使うだけで、実際の関数は見つかった
SQL ファイルのパスから導出される namespace に定義される。

`defrender` が `query-name` を解決する優先順位は次のとおり:

1. `/*:name */`
2. SQL ファイル名そのもの

`/*:name */` に namespace suffix が含まれない場合でも、ファイル名側で suffix を
与えられる。生成される var 名は、最終的に解決された `query-name` の最後の
セグメントになる。それ以前のセグメントは、SQL ファイルの親ディレクトリ由来
namespace の下へ付け足す suffix として使う。

例:

- `sql/postgresql/public/users/get-by-id.sql`
  -> `sql.postgresql.public.users.core/get-by-id`
- `sql/postgresql/public/users/hoge.list-order-by-created-at.sql`
  -> `sql.postgresql.public.users.hoge/list-order-by-created-at`
- `sql/postgresql/public/users/crud.sql` と `/*:name crud.get-by-id */`
  -> `sql.postgresql.public.users.crud/get-by-id`

ディレクトリを読む場合、ファイルはパス順に再帰処理する。var 名衝突は error とする。

`defquery` は、実行可能な query 関数を定義する高レイヤのファサードである。
デフォルトでは `:next-jdbc` アダプタへ委譲する。

## 6.4 実行アダプタ

実行は adapter namespace 配下で提供する。

例:

```clojure
(ns sql.postgresql.public.users.core
  (:require [bisql.core :as bisql]
            [bisql.adapter.next-jdbc :as bisql.jdbc]))

(bisql/defquery "/sql/postgresql/public/users/get-by-id.sql")

(bisql.jdbc/exec! datasource get-by-id {:id 42})
```

`bisql.adapter.next-jdbc/exec!` は、query function metadata の `:cardinality` を見て
`next.jdbc/execute-one!` または `next.jdbc/execute!` を選ぶ。`:cardinality` が
未指定の場合は `:many` をデフォルトとする。

これにより `bisql.core` は、読み込み・解析・レンダリング・関数生成に集中できる。

## 6.5 CRUD 生成

```clojure
(generate-crud datasource {:schema "public"})
```

PostgreSQL のスキーマメタデータから、クエリ定義、SQL template ファイル、
テーブルごとの query namespace ファイルを生成する。

例:

```clojure
(-> (generate-crud datasource {:schema "public"})
    (write-crud-files! {:output-root "src/sql"}))

(-> (generate-crud datasource {:schema "public"})
    (write-declaration-files! {:output-root "src/sql"}))
```

生成される namespace ファイルは、SQL テンプレートから導出した
docstring 付きの query var を `declare` する:

```clojure
(ns sql.postgresql.public.users.crud

(declare ^{:arglists '([datasource] [datasource template-params])
           :doc "..."}
 get-by-id)
```

同じ生成フローは CLI としても提供できる:

```sh
clojure -M -m bisql.cli gen-config
clojure -M -m bisql.cli gen-crud --config bisql.edn
clojure -M -m bisql.cli gen-declarations --config bisql.edn
```

設定ファイルは `:db` と `:generate` を持つ EDN map とし、生成される雛形では既定値をコメントで例示する。設定ファイルがなくても、優先順位が CLI オプション > 環境変数 > 設定ファイル > デフォルトなので各コマンドは動作する。

`gen-declarations` は補助機能として残す。浅い階層で `(defquery)` を呼んだときに、
未宣言の namespace に関数が定義されるのを避けたいプロジェクトや、
IDE / REPL で使うナビゲーション用の declare と docstring がほしい
プロジェクトでは、明示的な namespace ファイルを生成する用途で使える。
デフォルトでは docstring にはプロジェクトルートからの相対 SQL パスと行番号だけを含め、
SQL テンプレート本文も含めたい場合は `--include-sql-template` を使う。

**理由:**

- API の表面積を小さく保てる
- 読み込み、展開、実行アダプタ、生成の責務を分離できる
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

## 7.3 Upsert

以下の場合に生成する:

- 主キー
- unique 制約

PostgreSQL の `INSERT ... ON CONFLICT ON CONSTRAINT ... DO UPDATE RETURNING *` を使う。

```clojure
(upsert-by-id! db row)
```

複合キーの例:

```clojure
(upsert-by-user-id-and-device-identifier! db row)
```

生成される upsert query は、挿入時の値を `:inserting` に入れて受け取る。
また、衝突時に既存行の値を維持したい列は `:non-updating-cols` で指定できる:

```clojure
(users.crud/upsert-by-id
  datasource
  {:inserting {:email "alice@example.com"
               :display-name "Alice"
               :status "active"
               :created-at #inst "2026-04-12T00:00:00Z"}
   :non-updating-cols {:created-at true}})
```

このとき生成 SQL は `INSERT INTO ... AS t` を使い、
`:non-updating-cols.<column>` が truthy な列では
`EXCLUDED.<column>` の代わりに `t.<column>` を使う。
その結果、その列の値は変更されず既存値が維持される。

---

## 7.4 Update

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

## 7.5 Delete

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

## 7.8 LIMIT と OFFSET（必須）

すべての `list-by-*` 関数は `limit` と `offset` を必須とする。

```clojure
(list-by-customer-id db {:customer-id 10
                         :limit 100
                         :offset 0})
```

### ルール

- 正の整数でなければならない
- `offset` は 0 以上の整数でなければならない
- 初期実装では無制限オプションを持たない

**理由:**

- 意図しないフルスキャンを防ぐ
- 結果件数への意識を強制する
- 生成クエリのページネーション形を統一できる
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

- 範囲ベースクエリ
- カーソルベースページネーション
- より豊かなメタデータ注釈
- スキーマバリデーション連携（Malli）
