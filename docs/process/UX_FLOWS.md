# UX Flows

Mermaid diagrams documenting the navigation structure and key user journeys in Sushi.

---

## App Navigation Map

```mermaid
graph TD
    MA[MainActivity] --> TA[TerminalActivity]
    MA --> SA[SettingsActivity]
    MA --> HA[HostsActivity]
    MA --> HE[HostEditActivity]
    MA --> GD((Gemini Dialog))

    SA --> HA
    SA --> HE
    SA --> KA[KeysActivity]
    SA --> PA[PlaysActivity]
    SA --> PH[PhrasesActivity]
    SA --> AA[AboutActivity]

    HA --> HE

    TA --> PP((Phrase Picker))

    AA --> GH[GitHub ↗]

    style MA fill:#4a9,color:#fff
    style TA fill:#49a,color:#fff
    style SA fill:#a94,color:#fff
    style GD fill:#555,color:#fff,stroke-dasharray: 5 5
    style PP fill:#555,color:#fff,stroke-dasharray: 5 5
```

Dashed borders = dialogs (not separate activities).

---

## MainActivity Tabs

```mermaid
graph LR
    MA[MainActivity<br>ViewPager2] --> T[Tab 0: Terminal]
    MA --> P[Tab 1: Plays]

    T --> GV[Gemini Voice Button]
    GV --> GD((Gemini Dialog))

    P --> RP[Run Play]
    P --> MP[Manage Plays]
    P --> SL[Session Log]
```

---

## SettingsActivity Tabs

```mermaid
graph LR
    SA[SettingsActivity<br>ViewPager2] --> G[Tab 0: General]
    SA --> S[Tab 1: SSH]
    SA --> M[Tab 2: Gemini]
    SA --> D[Tab 3: Drive]

    G --> PH[Manage Phrases]
    G --> PA[Manage Plays]

    S --> HA[Manage Hosts]
    S --> HE[Add Host]
    S --> KA[Keys]
    S --> TC[Test Connection]

    M --> ND[Nano Download]
    M --> CM[Cloud Model Toggle]

    D --> SI[Sign In / Out]
    D --> AS[Auto-Save Toggle]
```

---

## Terminal Connection Flow

```mermaid
flowchart TD
    A[User taps Start Session] --> B{Config exists?}
    B -->|No| C[Toast: missing config]
    B -->|Yes| D[Connect attempt 1]
    D --> E{Success?}
    E -->|Yes| F[Terminal ready<br>keyboard active]
    E -->|No| G[Wait 1.2s]
    G --> H[Connect attempt 2]
    H --> I{Success?}
    I -->|Yes| F
    I -->|No| J[Show error<br>Toast + log]

    F --> K{User interaction}
    K --> L[Type / special keys]
    K --> M[Phrases button]
    K --> N[End Session]

    M --> O((Phrase Picker))
    O --> P[Send command + newline]
    P --> K

    L --> Q[sendRaw → SSH]
    Q --> K

    N --> R[Save log]
    R --> S[Disconnect]
    S --> T[Upload to Drive<br>if enabled]

    F --> U{Connection monitor<br>every 1.5s}
    U -->|Connected| U
    U -->|Lost| V[Toast: connection lost]
    V --> W[Show Reconnect button]
```

---

## Gemini Voice Flow

```mermaid
flowchart TD
    A[User taps Gemini button] --> B((Gemini Dialog))
    B --> C[User taps Voice]
    C --> D{Mic permission?}
    D -->|Denied| E[Request permission]
    E --> D
    D -->|Granted| F[System voice input]
    F --> G[Speech-to-text result]
    G --> H[Show prompt in dialog]
    H --> I{Nano preferred<br>& available?}
    I -->|Yes| J[On-device inference]
    I -->|No| K[Cloud API call]
    J --> L[Show suggested command]
    K --> L
    L --> M{User action}
    M --> N[Copy command]
    M --> O[Dismiss dialog]
    M --> P[Open settings]
```

---

## Play Execution Flow

```mermaid
flowchart TD
    A[User taps Run Play] --> B{Play running?}
    B -->|Yes| C[Toast: already running]
    B -->|No| D{Plays exist?}
    D -->|No| E[Toast → Manage Plays]
    D -->|Yes| F{Hosts exist?}
    F -->|No| G[Toast → Settings]
    F -->|Yes| H((Select Host))
    H --> I((Select Play))
    I --> J{Has parameters?}
    J -->|No| K[Run play]
    J -->|Yes| L((Parameter form))
    L --> M{Valid?}
    M -->|No| L
    M -->|Yes| K
    K --> N[Stream output to session log]
    N --> O{Success?}
    O -->|Yes| P[Log: play finished]
    O -->|No| Q[Log + Toast: failed]
    P --> R[Upload log to Drive<br>if enabled]
    Q --> R
```

---

## Host & Key Management Flow

```mermaid
flowchart TD
    A[Settings: SSH tab] --> B[Add Host]
    A --> C[Manage Hosts]
    A --> D[Keys]
    A --> E[Test Connection]

    B --> F[HostEditActivity]
    F --> G[Save → set active]

    C --> H[HostsActivity]
    H --> I[Tap host → set active]
    H --> J[Edit host]
    J --> F
    H --> K[Add host FAB]
    K --> F

    D --> L[KeysActivity]
    L --> M{Key exists?}
    M -->|No| N[Generate key pair]
    M -->|Yes| O[View / Delete key]

    E --> P[Run diagnostics]
    P --> Q[Show result + copy button]
```

---

## Phrase Management Flow

```mermaid
flowchart TD
    A[Settings: General tab] --> B[Manage Phrases]
    B --> C[PhrasesActivity]
    C --> D[Tap phrase → edit]
    C --> E[Delete phrase]
    C --> F[Add phrase FAB]
    C --> G[Export to clipboard]
    C --> H[Import from clipboard]

    D --> I((Edit Dialog))
    F --> I
    I --> J{Valid?}
    J -->|Name empty| K[Error: name required]
    J -->|Command empty| L[Error: command required]
    J -->|Duplicate name| M[Error: name exists]
    J -->|OK| N[Save]

    E --> O((Confirm delete))
    O -->|Yes| P[Remove phrase]
    O -->|No| C
```
