import SwiftUI

/// Android 版の colors.xml をそのまま持ってきた配色。
enum Theme {
    static let background = Color(hex: 0x020617)
    static let surface = Color(hex: 0x0F172A)
    static let cyan = Color(hex: 0x06B6D4)
    static let pink = Color(hex: 0xF472B6)
    static let lime = Color(hex: 0x84CC16)
    static let border = Color(hex: 0x1E293B)
    static let outlineHigh = Color(hex: 0x334155)
    static let accent = Color(hex: 0x3B82F6)
    static let error = Color(hex: 0xF43F5E)

    static let textPrimary = Color(hex: 0xF8FAFC)
    static let textSecondary = Color(hex: 0x94A3B8)
    static let textTertiary = Color(hex: 0x475569)

    static let hpBar = Color(hex: 0x10B981)
    static let mpBar = Color(hex: 0x3B82F6)
    static let expBar = Color(hex: 0xFBBF24)

    /// 優先度1〜5の色。0(完了)は最も淡い。
    static func priorityColor(_ priority: Int) -> Color {
        switch priority {
        case 5: return Color(hex: 0xF43F5E)
        case 4: return Color(hex: 0xFBBF24)
        case 3: return Color(hex: 0x3B82F6)
        case 2: return Color(hex: 0x10B981)
        default: return Color(hex: 0x475569)
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Android 版の CyberCard 相当。角を落とし、シアンの細い枠を付ける。
struct CyberCard<Content: View>: View {
    var accent: Color = Theme.cyan
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface.opacity(0.85))
            .overlay(
                RoundedRectangle(cornerRadius: 2)
                    .strokeBorder(accent.opacity(0.6), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 2))
    }
}

/// 画面上部の見出し。大文字・イタリック・字間広めで Android 版に寄せる。
struct CyberHeader: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 22, weight: .bold, design: .default))
            .italic()
            .kerning(2)
            .foregroundStyle(Theme.cyan)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// HP/EXP バー。
struct StatBar: View {
    let value: Double
    let color: Color

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Rectangle().fill(Theme.border)
                Rectangle()
                    .fill(color)
                    .frame(width: geometry.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 6)
        .clipShape(RoundedRectangle(cornerRadius: 3))
    }
}

extension View {
    /// 画面共通の背景。
    func curaBackground() -> some View {
        background(
            LinearGradient(
                colors: [Theme.background, Color(hex: 0x0B1120), Theme.background],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        )
    }
}
