# 凸型フローティング・ボトムナビゲーション実装指示プロンプト

以下をAI（ChatGPT / Claude / Cursor / Antigravity 等）にそのまま送信して使用してください。

---

## プロンプト本文

```
以下の仕様に基づいて、Android (Kotlin) アプリにMaterial 3標準のBottomNavigationViewを用いた「凸型フローティング・ボトムナビゲーション（Bulge Navigation）」を実装してください。
サードパーティの外部ライブラリは使用せず、標準のコンポーネントのみで完結させてください。

### 1. デザインと動作要件
- 画面下部に浮き上がる角丸のナビゲーションバー（左右・下部にマージンあり）。
- 選択中タブの背後に、バーの上端から突き出る円形インジケーター（Bulge Circle）を表示する。
- タブを切り替えると、背面の円がスムーズにスライドアニメーションする。
- 選択されたタブのアイコンは上方向（突起した円の中央付近）へリフトアップされ、非選択になると元の高さに戻る。
- Material 3の標準アクティブインジケーター（ピル型背景）は透明化して無効化する。
- メニュー項目の一部が動的に非表示（isVisible = false）になっても、各タブの中央位置が狂わずに正しく追従すること。

---

### 2. レイアウト構成（3層構造）
FrameLayoutをコンテナとし、クリップを無効化（clipChildren="false", clipToPadding="false"）した上で以下の3層を重ねてください。

- Layer 1 (最背面): MaterialCardView（70dp x 70dp, cornerRadius 35dp, バーと同色）。バー上部に突き出るよう layout_marginBottom を調整。
- Layer 2 (中間): View（高さ80dp）。角丸のDrawable背景（bg_nav_base.xml）。
- Layer 3 (最前面): BottomNavigationView（高さ140dp, 背景透明, clipChildren="false", clipToPadding="false", elevation="20dp"）。itemPaddingTop/Bottomで初期位置を調整。

【layout.xml 実装例】
<FrameLayout
    android:layout_width="match_parent"
    android:layout_height="150dp"
    android:layout_marginHorizontal="12dp"
    android:layout_marginBottom="8dp"
    android:clipChildren="false"
    android:clipToPadding="false">

    <!-- Layer 1: 移動する突起サークル -->
    <com.google.android.material.card.MaterialCardView
        android:id="@+id/navBulgeCircle"
        android:layout_width="70dp"
        android:layout_height="70dp"
        android:layout_gravity="bottom|start"
        android:layout_marginBottom="65dp"
        app:cardBackgroundColor="@color/nav_bar_color"
        app:cardCornerRadius="35dp"
        app:cardElevation="0dp"
        app:strokeWidth="0dp" />

    <!-- Layer 2: メインフローティングバー -->
    <View
        android:layout_width="match_parent"
        android:layout_height="80dp"
        android:layout_gravity="bottom"
        android:layout_marginBottom="30dp"
        android:background="@drawable/bg_nav_base"
        android:elevation="0dp" />

    <!-- Layer 3: 操作ロジック本体 -->
    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNavigation"
        style="@style/BulgeBottomNavigation"
        android:layout_width="match_parent"
        android:layout_height="140dp"
        android:layout_gravity="bottom"
        android:background="@android:color/transparent"
        android:elevation="20dp"
        android:translationZ="20dp"
        android:clipChildren="false"
        android:clipToPadding="false"
        app:backgroundTint="@android:color/transparent"
        app:itemIconTint="@color/selector_nav_color"
        app:itemTextColor="@color/selector_nav_color"
        app:itemPaddingTop="44dp"
        app:itemPaddingBottom="16dp"
        app:labelVisibilityMode="labeled"
        app:menu="@menu/bottom_nav_menu" />
</FrameLayout>

---

### 3. リソース定義

【res/drawable/bg_nav_base.xml】
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/nav_bar_color" />
    <corners
        android:topLeftRadius="24dp"
        android:topRightRadius="24dp"
        android:bottomLeftRadius="16dp"
        android:bottomRightRadius="16dp" />
</shape>

【res/color/selector_nav_color.xml】
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="@color/nav_selected_color" android:state_checked="true" />
    <item android:color="@color/nav_unselected_color" />
</selector>

【res/values/themes.xml】
<style name="BulgeBottomNavigation" parent="Widget.Material3.BottomNavigationView">
    <item name="itemIconSize">28dp</item>
    <item name="itemTextAppearanceActive">@style/NavText.Active</item>
    <item name="itemTextAppearanceInactive">@style/NavText.Inactive</item>
    <item name="itemIconTint">@color/selector_nav_color</item>
    <item name="itemTextColor">@color/selector_nav_color</item>
    <item name="backgroundTint">@android:color/transparent</item>
    <item name="itemActiveIndicatorStyle">@style/TransparentActiveIndicator</item>
</style>

<style name="NavText.Active" parent="TextAppearance.Material3.LabelSmall">
    <item name="android:textSize">12sp</item>
    <item name="android:textStyle">bold</item>
    <item name="android:textColor">@color/nav_selected_color</item>
</style>

<style name="NavText.Inactive" parent="TextAppearance.Material3.LabelSmall">
    <item name="android:textSize">11sp</item>
    <item name="android:textColor">@color/nav_unselected_color</item>
</style>

<style name="TransparentActiveIndicator" parent="Widget.Material3.BottomNavigationView.ActiveIndicator">
    <item name="android:color">@android:color/transparent</item>
</style>

---

### 4. Kotlin 実装コード

private fun animateNavBulge(itemId: Int) {
    val nav = binding.bottomNavigation
    val bulge = binding.navBulgeCircle

    // 1. 実際に表示されているメニュー項目のみを抽出
    val visibleItems = mutableListOf<Int>()
    for (i in 0 until nav.menu.size()) {
        val item = nav.menu.getItem(i)
        if (item.isVisible) {
            visibleItems.add(item.itemId)
        }
    }
    val visibleCount = visibleItems.size
    if (visibleCount == 0) return

    val index = visibleItems.indexOf(itemId).coerceAtLeast(0)

    nav.post {
        val totalWidth = nav.width
        val itemWidth = totalWidth / visibleCount
        val targetX = (itemWidth * index) + (itemWidth / 2) - (bulge.width / 2)

        // 2. 突起サークルのスライドアニメーション
        bulge.animate()
            .translationX(targetX.toFloat())
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // 3. アイコン・文字のリフトアップ処理（クリッピングを解除しながら上方向へ移動）
        val menuView = nav.getChildAt(0) as? android.view.ViewGroup ?: return@post
        menuView.clipChildren = false
        menuView.clipToPadding = false

        var visibleViewIndex = 0
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
            itemView.clipChildren = false
            itemView.clipToPadding = false

            if (itemView.visibility != View.VISIBLE) continue

            if (itemView.childCount > 0) {
                val icon = itemView.getChildAt(0)
                if (icon is android.view.ViewGroup) {
                    icon.clipChildren = false
                    icon.clipToPadding = false
                }

                if (visibleViewIndex == index) {
                    // 選択アイテム: アイコンを持ち上げ、ラベルもわずかに引き上げる
                    icon.animate().translationY(-64f).setDuration(300).start()
                    if (itemView.childCount > 1) {
                        val label = itemView.getChildAt(1)
                        label.animate().translationY(-12f).setDuration(300).start()
                    }
                } else {
                    // 非選択アイテム: 通常の基準位置へ戻す
                    icon.animate().translationY(0f).setDuration(250).start()
                    if (itemView.childCount > 1) {
                        val label = itemView.getChildAt(1)
                        label.animate().translationY(0f).setDuration(250).start()
                    }
                }
            }
            visibleViewIndex++
        }
    }
}

// リスナー登録・初期化
binding.bottomNavigation.setOnItemSelectedListener { item ->
    animateNavBulge(item.itemId)
    // ここに画面遷移処理
    true
}
binding.bottomNavigation.selectedItemId = R.id.nav_home
```
