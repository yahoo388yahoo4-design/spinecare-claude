import SwiftUI
import AVKit

@main
struct SpineCareApp: App {
    var body: some Scene { WindowGroup { RootView() } }
}

struct RootView: View {
    @ObservedObject private var lang = L.shared   // re-render everything on language switch
    @State private var tab = 0                     // kept across the re-render, so Settings stays open

    var body: some View {
        TabView(selection: $tab) {
            HomeView().tabItem { Label(L.t("tab_home"), systemImage: "house") }.tag(0)
            ExercisesView().tabItem { Label(L.t("tab_plan"), systemImage: "figure.walk") }.tag(1)
            SessionsView().tabItem { Label(L.t("tab_progress"), systemImage: "chart.line.uptrend.xyaxis") }.tag(2)
            SensorsView().tabItem { Label(L.t("tab_sensors"), systemImage: "dot.radiowaves.left.and.right") }.tag(3)
            SettingsView().tabItem { Label(L.t("tab_settings"), systemImage: "gearshape") }.tag(4)
        }
        .id(lang.lang)
    }
}

struct HomeView: View {
    @ObservedObject var store = SessionStore.shared
    @State private var neck = 3.0
    @State private var arm = 2.0
    @State private var sleep = 6.0
    @State private var note = ""
    @State private var saved = ""
    @State private var showSafety = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L.t("status_prototype")).font(.caption).foregroundStyle(.secondary)
                    Text(L.t("welcome")).font(.title3.bold())
                    Text(L.t("intro"))
                    Button(L.t("lang_switch")) { L.toggle() }
                }
                Section(L.t("safety")) {
                    Text(L.t("safety_body")).font(.callout)
                    Text(L.t("red_flags")).font(.callout).foregroundStyle(.red)
                    Button(L.t("got_it")) { showSafety = true }
                }
                Section(L.t("followup")) {
                    Text(L.t("followup_mri")).font(.callout)
                    Text(L.t("followup_physio")).font(.callout)
                }
                Section(L.t("plan")) {
                    let all = Plan.all
                    Text(L.f("plan_status", all.filter { $0.isCustom || store.isApproved($0.id) }.count, all.count))
                    Text(L.t("not_advice")).font(.caption).foregroundStyle(.secondary)
                }
                Section(L.t("daily_checkin")) {
                    scale(L.t("neck_pain"), $neck)
                    scale(L.t("arm_symptoms"), $arm)
                    scale(L.t("sleep_quality"), $sleep)
                    TextField(L.t("note_hint"), text: $note, axis: .vertical)
                    Text(L.t("pain_warning")).font(.caption).foregroundStyle(.secondary)
                    Button(L.t("save_checkin")) {
                        store.saveCheckin(phase: "daily", neckPain: Int(neck), armSymptoms: Int(arm),
                                          sleepQuality: Int(sleep), note: note.trimmingCharacters(in: .whitespacesAndNewlines))
                        note = ""; saved = L.t("saved_local")
                    }
                    if !saved.isEmpty { Text(saved).foregroundStyle(.green) }
                }
                Section(L.t("recent")) {
                    if store.sessions.isEmpty { Text(L.t("no_sessions")) }
                    ForEach(store.sessions.prefix(6)) { s in
                        Text("\(s.date)  \(Plan.byId(s.exercise)?.title(L.lang) ?? s.exercise)  \(L.t("reps"))=\(s.reps)  [\(s.source)]")
                            .font(.caption)
                    }
                }
                Section(L.t("privacy")) { Text(L.t("privacy_body")).font(.caption) }
            }
            .navigationTitle("SpineCare")
            .alert(L.t("safety"), isPresented: $showSafety) { Button(L.t("got_it")) {} } message: {
                Text(L.t("safety_body") + "\n\n" + L.t("red_flags"))
            }
        }
    }

    private func scale(_ label: String, _ v: Binding<Double>) -> some View {
        VStack(alignment: .leading) {
            Text("\(label): \(Int(v.wrappedValue))")
            Slider(value: v, in: 0...10, step: 1)
        }
    }
}

struct ExercisesView: View {
    @ObservedObject var store = SessionStore.shared
    @State private var name = ""
    @State private var instructions = ""
    @State private var sets = ""
    @State private var reps = ""
    @State private var video = ""
    @State private var approved = false
    @State private var formError = ""
    @AppStorage("programFilter") private var programFilter = "all"

    var body: some View {
        NavigationStack {
            List {
                Section { Text("⚠ " + L.t("approval_warning")).font(.callout).foregroundStyle(.red) }
                Section {
                    Picker(L.t("program_pick"), selection: $programFilter) {
                        ForEach(["all"] + Plan.programs, id: \.self) { Text(L.t("program_short_" + $0)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    if programFilter != "all" {
                        Text(L.t("program_hint_" + programFilter)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                ForEach(Plan.builtIn(in: programFilter) + store.customExercises) { ex in
                    Section { row(ex) }
                }
                Section(L.t("custom_title")) {
                    Text(L.t("custom_empty")).font(.caption).foregroundStyle(.secondary)
                    TextField(L.t("exercise_name"), text: $name)
                    TextField(L.t("instructions"), text: $instructions, axis: .vertical)
                    TextField(L.t("target_sets"), text: $sets).keyboardType(.numberPad)
                    TextField(L.t("target_reps"), text: $reps).keyboardType(.numberPad)
                    TextField(L.t("video_url"), text: $video).keyboardType(.URL).textInputAutocapitalization(.never)
                    Toggle(L.t("approval_check"), isOn: $approved)
                    Button(L.t("save_exercise")) { saveCustom() }
                    if !formError.isEmpty { Text(formError).font(.caption).foregroundStyle(.red) }
                }
                Section { Text(L.t("reps_rule") + " " + L.t("stop_rule")).font(.caption).foregroundStyle(.orange) }
            }
            .navigationTitle(L.t("tab_plan"))
            .navigationDestination(for: Exercise.self) { ex in
                if ex.cameraTracked { ExerciseSessionView(exercise: ex) } else { ManualSessionView(exercise: ex) }
            }
        }
    }

    @ViewBuilder private func row(_ ex: Exercise) -> some View {
        let lang = L.lang
        let ok = ex.isCustom || store.isApproved(ex.id)
        VStack(alignment: .leading, spacing: 4) {
            Text(ex.title(lang)).font(.headline)
            Text(L.t("program_" + ex.programId)).font(.caption).foregroundStyle(.blue)
            Text(ex.cameraTracked ? L.f("meta_camera", L.t("view_" + ex.view), ex.targetReps)
                                  : L.f("meta_manual", ex.targetSets ?? 0, ex.targetReps))
                .font(.caption).foregroundStyle(.secondary)
            Text(ex.details(lang)).font(.callout)
            if !ex.stops(lang).isEmpty {
                Text(L.t("stop_if") + " " + ex.stops(lang).joined(separator: ", ")).font(.caption).foregroundStyle(.orange)
            }
        }
        if ex.cameraTracked { NavigationLink(L.t("gd_open")) { GuideView(exercise: ex) } }
        if let v = ex.videoUrl, !v.isEmpty, let url = URL(string: v) { Link(L.t("open_video"), destination: url) }
        if ex.isCustom {
            Label(L.t("approved"), systemImage: "checkmark.seal").foregroundStyle(.green)
            Button(L.t("delete_custom"), role: .destructive) { store.removeCustom(ex.id) }
        } else {
            Toggle(L.t("approval_check"), isOn: Binding(get: { store.isApproved(ex.id) }, set: { store.setApproved(ex.id, $0) }))
        }
        if ok {
            NavigationLink(ex.cameraTracked ? L.t("start_guided") : L.t("start_manual"), value: ex)
        } else {
            Text(L.t("approved_required")).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func saveCustom() {
        let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        let d = String(instructions.trimmingCharacters(in: .whitespacesAndNewlines).prefix(600))
        let v = String(video.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
        guard !n.isEmpty, !d.isEmpty, approved else { formError = L.t("custom_empty"); return }
        guard v.isEmpty || v.hasPrefix("https://") else { formError = L.t("video_https"); return }
        store.addCustom(.makeCustom(name: n, instructions: d, sets: min(99, max(0, Int(sets) ?? 0)),
                                    reps: min(999, max(0, Int(reps) ?? 0)), videoUrl: v))
        name = ""; instructions = ""; sets = ""; reps = ""; video = ""; approved = false; formError = ""
    }
}

/// Timer + set counter for custom exercises the camera does not track.
struct ManualSessionView: View {
    let exercise: Exercise
    @State private var startedAt: Date?
    @State private var sets = 0
    @State private var now = Date()
    @State private var saved = false
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        let lang = L.lang
        List {
            Text(exercise.details(lang))
            Text(L.f("meta_manual", exercise.targetSets ?? 0, exercise.targetReps)).font(.caption)
            Text(L.t("reps_rule") + " " + L.t("stop_rule")).font(.caption).foregroundStyle(.orange)
            let s = startedAt.map { Int(now.timeIntervalSince($0)) } ?? 0
            Text(String(format: "%02d:%02d", s / 60, s % 60)).font(.system(size: 44, weight: .bold, design: .monospaced))
            Text(L.f("sets_done", sets, exercise.targetSets ?? 0)).font(.title3)
            if startedAt == nil && !saved {
                Button(L.t("start")) { startedAt = Date(); sets = 0 }
            } else if !saved {
                Button(L.t("set_done")) { sets += 1 }
                Button(L.t("stop_save")) {
                    SessionStore.shared.saveSession(exerciseId: exercise.id, reps: sets * exercise.targetReps, sets: sets,
                                                    durationS: s, metrics: [:], cues: [:], frames: 0, source: "manual",
                                                    videoURL: nil, recordedFrames: 0)
                    saved = true; startedAt = nil
                }
                Button(L.t("cancel_session"), role: .destructive) { startedAt = nil }
            } else {
                Text(L.t("saved_local")).foregroundStyle(.green)
            }
            Text(L.t("pain_warning")).font(.caption).foregroundStyle(.secondary)
        }
        .navigationTitle(exercise.title(lang))
        .onReceive(tick) { now = $0 }
    }
}

/// Progress: sessions (thumbnail + in-app replay), check-ins, CSV export and clearing history.
struct SessionsView: View {
    @ObservedObject var store = SessionStore.shared
    @State private var confirmClear = false
    @State private var exportURL: URL?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L.t("progress_body")).font(.caption).foregroundStyle(.secondary)
                    Text(L.f("stats", store.sessions.count, store.sessions.reduce(0) { $0 + $1.reps }, store.checkins.count))
                    let trend = store.checkins.prefix(7).reversed().map { String($0.neckPain) }.joined(separator: "  ")
                    if !trend.isEmpty { Text(L.t("pain_trend") + " " + trend).font(.caption) }
                }
                Section(L.t("saved_sessions")) {
                    if store.sessions.isEmpty { Text(L.t("no_sessions_long")).font(.callout).foregroundStyle(.secondary) }
                    ForEach(store.sessions) { rec in
                        let canReplay = store.videoExists(rec) || store.timelineURL(rec) != nil
                        HStack(alignment: .top, spacing: 10) {
                        if canReplay { SessionThumb(rec: rec) }
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(rec.date) · \(Plan.byId(rec.exercise)?.title(L.lang) ?? rec.exercise)").font(.headline)
                            Text("\(L.t("reps"))=\(rec.reps) · \(L.t("sets"))=\(rec.sets ?? 0) · \(L.t("duration"))=\(rec.durationS ?? 0)s · [\(rec.source)]")
                                .font(.caption).foregroundStyle(.secondary)
                            if !rec.avg.isEmpty {
                                Text(rec.avg.sorted { $0.key < $1.key }.map { "\($0.key)=\(String(format: "%.2f", $0.value))" }
                                        .joined(separator: "  "))
                                    .font(.system(.caption2, design: .monospaced)).foregroundStyle(.secondary)
                            }
                            if canReplay {
                                NavigationLink(L.t("replay_open")) { SessionReplayView(rec: rec) }
                            }
                            if rec.video != nil && !store.videoExists(rec) {
                                Text(L.t("recording_missing")).font(.caption).foregroundStyle(.red)
                            }
                        }
                        }
                    }
                }
                Section(L.t("checkins")) {
                    if store.checkins.isEmpty { Text(L.t("no_checkins")) }
                    ForEach(store.checkins.prefix(30)) { c in
                        VStack(alignment: .leading) {
                            Text("\(c.date)  \(L.t("phase_" + c.phase))  \(L.t("neck_pain"))=\(c.neckPain)  \(L.t("arm_symptoms"))=\(c.armSymptoms)"
                                 + (c.sleepQuality.map { "  \(L.t("sleep_quality"))=\($0)" } ?? "")).font(.caption)
                            if !c.notes.isEmpty { Text(c.notes).font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                }
                Section(L.t("export_title")) {
                    Text(L.t("export_body")).font(.caption)
                    if let url = exportURL { ShareLink(L.t("export_title"), item: url) }
                    Button(L.t("clear_history"), role: .destructive) { confirmClear = true }
                }
            }
            .navigationTitle(L.t("progress"))
            .onAppear { store.load(); exportURL = store.exportCSV() }
            .onChange(of: store.sessions.count) { _ in exportURL = store.exportCSV() }
            .onChange(of: store.checkins.count) { _ in exportURL = store.exportCSV() }
            .confirmationDialog(L.t("confirm_clear"), isPresented: $confirmClear, titleVisibility: .visible) {
                Button(L.t("delete"), role: .destructive) { store.clearHistory() }
            }
        }
    }
}
