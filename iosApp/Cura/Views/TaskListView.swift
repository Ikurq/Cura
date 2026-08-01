import Shared
import SwiftUI

struct TaskListView: View {
    @EnvironmentObject private var model: AppModel

    @State private var editing: TaskDraft?
    @State private var selection: Set<String> = []

    private var pending: [TaskItem] { model.tasks.filter { !$0.isCompleted } }
    private var completed: [TaskItem] { model.tasks.filter { $0.isCompleted } }

    var body: some View {
        NavigationStack {
            Group {
                if model.tasks.isEmpty {
                    emptyState
                } else {
                    List {
                        Section("進行中") {
                            ForEach(pending, id: \.id) { row($0) }
                                .onDelete { offsets in
                                    Task { for index in offsets { await model.deleteTask(pending[index]) } }
                                }
                        }
                        if !completed.isEmpty {
                            Section("完了") {
                                ForEach(completed, id: \.id) { row($0) }
                                    .onDelete { offsets in
                                        Task { for index in offsets { await model.deleteTask(completed[index]) } }
                                    }
                            }
                        }
                    }
                    .listRowBackground(Theme.surface)
                    .scrollContentBackground(.hidden)
                }
            }
            .curaBackground()
            .navigationTitle("タスクリスト")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !selection.isEmpty {
                        Button("\(selection.count)件を完了") {
                            Task {
                                await model.completeTasks(Array(selection))
                                selection.removeAll()
                            }
                        }
                        .foregroundStyle(Theme.lime)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editing = TaskDraft() } label: { Image(systemName: "plus") }
                }
            }
            .sheet(item: $editing) { draft in
                TaskEditorView(draft: draft).environmentObject(model)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "checklist").font(.largeTitle).foregroundStyle(Theme.textTertiary)
            Text("タスクがありません").foregroundStyle(Theme.textSecondary)
            Text("＋ボタンから最初のタスクを追加しましょう")
                .font(.footnote).foregroundStyle(Theme.textTertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func row(_ task: TaskItem) -> some View {
        let priority = Int(task.currentPriority(nowMillis: model.nowMillis()))

        return HStack(spacing: 12) {
            if !task.isCompleted {
                Button {
                    if selection.contains(task.id) { selection.remove(task.id) } else { selection.insert(task.id) }
                } label: {
                    Image(systemName: selection.contains(task.id) ? "checkmark.square.fill" : "square")
                        .foregroundStyle(selection.contains(task.id) ? Theme.lime : Theme.textTertiary)
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(task.title)
                    .font(.system(size: 15, weight: .medium))
                    .strikethrough(task.isCompleted)
                    .foregroundStyle(task.isCompleted ? Theme.textTertiary : Theme.textPrimary)
                Text(deadlineLabel(task))
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
            }

            Spacer()

            if !task.isCompleted {
                Text("P\(priority)")
                    .font(.system(size: 11, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Theme.priorityColor(priority))
                    .foregroundStyle(.white)
                    .clipShape(Capsule())
            }
        }
        .listRowBackground(Theme.surface)
        .contentShape(Rectangle())
        .onTapGesture { editing = TaskDraft(task: task) }
    }

    private func deadlineLabel(_ task: TaskItem) -> String {
        let date = CuraTime.shared.toLocalDateTime(millis: task.deadlineMillis)
        return String(format: "%d/%d %02d:%02d", date.monthNumber, date.dayOfMonth, date.hour, date.minute)
    }
}

final class TaskDraft: ObservableObject, Identifiable {
    let id: String
    @Published var title: String
    @Published var deadline: Date
    @Published var basePriority: Int
    let isCompleted: Bool
    let isNew: Bool

    init() {
        id = UUID().uuidString
        title = ""
        deadline = Calendar.current.date(bySettingHour: 23, minute: 59, second: 0, of: Date()) ?? Date()
        basePriority = 3
        isCompleted = false
        isNew = true
    }

    init(task: TaskItem) {
        id = task.id
        title = task.title
        deadline = Date(timeIntervalSince1970: Double(task.deadlineMillis) / 1000)
        basePriority = Int(task.basePriority)
        isCompleted = task.isCompleted
        isNew = false
    }

    func build() -> TaskItem {
        TaskItem(
            id: id,
            title: title,
            deadlineMillis: Int64(deadline.timeIntervalSince1970 * 1000),
            basePriority: Int32(basePriority),
            linkedEventId: nil,
            isCompleted: isCompleted
        )
    }
}

struct TaskEditorView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var draft: TaskDraft

    var body: some View {
        NavigationStack {
            Form {
                Section("内容") {
                    TextField("タスク名", text: $draft.title)
                }
                Section("締切") {
                    DatePicker("期限", selection: $draft.deadline)
                }
                Section("優先度") {
                    Picker("基本優先度", selection: $draft.basePriority) {
                        ForEach(1...5, id: \.self) { Text("P\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Text("締切が当日なら P5、翌日なら P4、翌々日なら P3 に自動で上がります。")
                        .font(.caption)
                        .foregroundStyle(Theme.textTertiary)
                    Text("完了時の獲得EXP: \(draft.build().expReward)")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .scrollContentBackground(.hidden)
            .curaBackground()
            .navigationTitle(draft.isNew ? "新しいタスク" : "タスクを編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        Task {
                            await model.saveTask(draft.build())
                            dismiss()
                        }
                    }
                    .disabled(draft.title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
