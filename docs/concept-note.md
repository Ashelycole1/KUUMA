# Kuuma — Concept Note: Victim Protection & Investigation Module

Nov 5, 2026 · @Niwasiima Ashelycole

## Executive summary

Kuuma (Luganda for "to guard") is an on-device safety layer that detects cyberbullying and harassment in the messages and notifications a person receives. It then turns each incident into court-ready evidence. The first module serves the victim, whether a child, a teenager or an adult. It reads incoming messages, flags abusive ones and captures them automatically, advises the victim and alerts a trusted guardian when risk is high. It all runs silently: the offender is never alerted, and victims and reporters stay anonymous.

What sets Kuuma apart is investigation. Most safety apps stop at blocking. Kuuma treats every flagged message as a potential exhibit. It captures the message with metadata, hashes it, links it to an offender profile and builds a timeline. A victim, parent, school or police officer can then act on a complete, tamper-evident case file rather than a few screenshots.

A multilingual LLM layer makes detection work the way people in Uganda and East Africa actually write: English, Luganda, Runyankole, Swahili and the code-switched slang that keyword filters miss.

## Problem statement

Victims of online abuse in Uganda rarely report it, and when they do, the case usually collapses for lack of usable evidence.

- **Abuse is common.** In UNICEF's youth poll, 34% of respondents in sub-Saharan Africa said they had been bullied online ([PML Daily](https://pmldaily.com/news/2019/09/more-than-a-third-of-young-people-in-the-world-report-being-victims-of-online-bullying.html)).
- **It happens where Kuuma can see it.** The Disrupting Harm Uganda study found that most online abuse of children happens on Facebook and WhatsApp, usually by someone the child already knows ([The Independent](https://independent.co.ug/200000-children-abused-online); [ECPAT](https://ecpat.org/?p=12875)).
- **Victims stay silent.** A third of children who were abused online told no one, citing stigma or a belief that nothing would change ([Hugh James summary of Disrupting Harm](https://www.hughjames.com/?p=10621)).
- **The system cannot act on what it receives.** Police, courts and social services lacked the awareness and capacity to handle online abuse cases ([UNICEF Uganda](https://www.unicef.org/uganda/stories/uganda-develop-national-action-plan-online-child-sexual-abuse-and-exploitation)). Victims felt they were not taken seriously ([The Independent](https://independent.co.ug/200000-children-abused-online/2)).
- **Tools do not speak local languages.** Platform filters are trained mostly on English. Insults and threats in Luganda, Runyankole, Swahili or Sheng, often mixed with English, pass through unflagged.
- **Evidence is fragile.** Messages get deleted, accounts get renamed, and screenshots are easy to dispute. Nothing links one offender's messages across apps and months into a single pattern.

## Proposed solution and objectives

Kuuma's victim module is an Android app plus a custom keyboard. Together they watch incoming messages, intervene in the moment and quietly build a case file in the background. It has four jobs: **detect, support, alert and investigate**.

Objectives for the first release:

1. Detect bullying, harassment, threats, sextortion and grooming in incoming notifications and in-app messages across WhatsApp, SMS, Facebook Messenger, Instagram, TikTok emails and X.
2. Classify messages in English, Luganda, Runyankole and Swahili, including mixed-language and slang messages.
3. Give the victim calm, specific guidance at the moment of harm: what to do, what not to reply, and who to tell.
4. Alert a pre-approved guardian when severity crosses a set threshold, without exposing the victim's whole inbox.
5. Preserve every flagged message as tamper-evident evidence and assemble incidents into an exportable case file for schools, police, platforms or lawyers and parents .

## Core features

The victim experiences Kuuma through four features. Each one also feeds the investigation module.

### 1. Message and notification detection

- An Android notification listener reads the text of incoming notifications from messaging and social apps the user has opted in.
- **Automatic capture:** the moment a message is flagged, Kuuma captures the full conversation thread in the background through an accessibility-based reader. Nobody has to tap anything, and messages that notifications cut short are still captured in full.
- SMS is read from its notification by the same listener, so Kuuma asks for no separate SMS permission.
- Each message is scored on-device first. Only uncertain or high-risk messages go to the cloud LLM for deeper analysis, with names and numbers removed.
- Detected categories: insults and humiliation, repeated harassment, threats of violence, sexual harassment, sextortion and image-based abuse, grooming patterns, doxxing (sharing someone's address, school or phone number), hate based on tribe, religion or gender, and encouragement of self-harm.

### 2. Kuuma Keyboard (Grammarly-style)

A custom keyboard the victim sets as default. It sits where the victim types replies, so it can step in before a situation escalates.

- **Context banner:** when the open chat has flagged messages, a strip above the keys shows the severity and a one-line tip.
- **Reply check:** it underlines draft replies that may escalate the situation, such as retaliation, threats back, or sharing a location, photos or personal details, and suggests a safer wording.
- **Safe replies:** one-tap responses such as a calm boundary ("Stop messaging me"), or no reply at all when silence is safer.
- **Backup capture:** capture is automatic, but a shield key lets the victim save anything Kuuma missed.
- **Quiet SOS:** a long-press that alerts the guardian without leaving the chat.
- The keyboard works in all four supported languages and suggests replies in the language the victim is typing.

### 3. Flagging and advice

Every flagged message gets a severity level and a response matched to it.

| Severity | Examples | What Kuuma does |
| --- | --- | --- |
| Low | One-off insult, teasing | Soft flag, coping tip, logged quietly |
| Medium | Repeated insults, exclusion, rumours, doxxing | Flag, step-by-step advice (mute, block, report), evidence saved, guardian optional |
| High | Threats of violence, sextortion, grooming, self-harm encouragement | Strong warning, do-not-reply guidance, evidence locked, guardian alerted, helpline shown |

Advice is written for the victim's age and language. It explains why the message is harmful, so victims stop blaming themselves. It also gives concrete steps: how to block and report on that specific platform, what not to delete, and when to involve an adult or the police. For self-harm risk, Kuuma shows Uganda's child helpline (Sauti 116) and other crisis contacts.

### 4. Guardian alerts

- Kuuma is two-sided: a parent app and a child app. For a child, the parent installs Kuuma on the child's phone, links it to the parent app and sets the child's age bracket. Adults set Kuuma up for themselves and link their own guardians, such as a parent, teacher, school counsellor or trusted adult.
- Guardians receive a summary, not the raw inbox: severity, category, platform, time and the offending message only.
- High-severity alerts arrive as a push notification  for guardians .
- The age bracket the parent sets decides which alerts reach the parent, and the child cannot change it. The brackets and thresholds are still to be agreed, for example younger children sending every flag and older teens sending medium and high. High-severity alerts always go through, and the app says so clearly during setup.
- Guardians get their own guidance on how to talk to the victim, what to avoid and how to report.

## Investigation module

The investigation module turns scattered abusive messages into one verified case that a school, police officer or lawyer can act on. It runs in the background from the first flag, so evidence exists even if the victim never opens the app.

&#91;embedded content: evidence pipeline · 6 steps, 4 destinations\]

Capture and sealing happen on the phone the moment a message is flagged. Linking, timelines and the case file build up over days or months as more messages arrive.

### Evidence capture

- For each flagged message, Kuuma stores the text, sender name, handle or phone number, platform, received time, device time and network time.
- It also captures the five messages before and after for context, and a screenshot of the thread, all automatically.
- Messages are saved before the offender can delete them. A "deleted for everyone" message that was already captured stays in the vault.
- Media such as images and voice notes are kept by reference and hash. Sexual images of minors are never copied, viewed or stored by Kuuma (see Privacy section).

### Integrity and chain of custody

- Every item gets a SHA-256 hash and is signed with a key held in the phone's secure hardware (Android Keystore).
- Items join an append-only hash chain, so removing or editing any entry breaks the chain visibly.
- A trusted timestamp from the server proves each item existed at a given time.
- A custody log records every view, export and share: who, when, and which items.
- Any recipient can re-check an exported item against its hash with a public verification page.

### Offender linking

- Messages from the same phone number, handle or account ID are grouped automatically.
- The LLM suggests likely links across renamed or new accounts using writing style, repeated phrases, shared details and timing. The victim or investigator confirms each link. Kuuma never confirms one on its own.
- Each offender profile shows message count, platforms used, first and last contact, and severity trend.

### Timeline and pattern analysis

- Incidents are laid out on one timeline across all apps.
- Kuuma measures escalation: frequency, severity and time of day, such as late-night messaging.
- For grooming, it tracks known stages: trust-building, gifts or flattery, isolation, sexual talk, requests for secrecy, and requests to meet or send images. Reaching a later stage triggers a high-severity alert.

### Case file and reporting

One tap produces a case file as a PDF and a machine-readable bundle. It contains:

1. A plain-language summary of what happened, who did it, and for how long
2. The timeline and offender profile
3. Each exhibit with its original text, an English translation clearly marked as machine-made, and its hash and timestamps
4. The relevant offence it may fall under, such as cyber harassment under section 24 of the Computer Misuse Act, labelled as guidance, not legal advice
5. The custody log and verification instructions

The file has templates for each destination: a school's disciplinary process, a police statement for the cybercrime unit, a platform report (WhatsApp, Meta, TikTok) and a lawyer's brief. Nothing leaves the phone unless the victim, or a guardian for younger children, approves the share.

## Silent and anonymous by design

Kuuma works without the offender ever knowing, and no one involved has to reveal who they are unless they choose to.

### Invisible to the offender

- Kuuma never replies, reacts, opens the chat or triggers read receipts. It reads from notifications and the screen, so the platform and the sender see no difference.
- Detection, capture, sealing and guardian alerts all run in the background, with no pop-up during the conversation.
- Advice appears only in the victim's private Kuuma space or the keyboard strip, never inside the chat.

### Discreet on the device

- An optional neutral app name and icon, so anyone picking up the phone does not see a safety app.
- The vault opens only with a PIN or fingerprint, and a quick-hide gesture closes Kuuma instantly.
- Kuuma's own notifications are worded neutrally and hidden from the lock screen.

### Anonymous end to end

- In the cloud, each victim is a random case ID. Names, phone numbers and photos stay on the phone.
- Messages sent for cloud analysis have contact details replaced with placeholders.
- Offenders appear as pseudonymous profiles (Offender A, Offender B). Their real identifiers stay in the encrypted vault.
- Reports to schools, platforms and police can be filed anonymously under the case ID. The victim's identity is revealed only if they choose, for example when a court requires it.
- Guardians see the case ID and the harmful content, not the victim's contacts or other chats.
- Vault encryption keys stay on the device, so Kuuma staff cannot read case contents.

Silent does not mean hidden from the person being protected. An adult agrees once during setup; for a child, the parent agrees when installing it. This keeps Kuuma within Google Play rules and the Data Protection and Privacy Act, and stops it from being used as spyware on someone else's phone.

## Multilingual LLM layer

Kuuma pairs a Ugandan-language model with a general reasoning model, so it understands what was said and judges how harmful it is in context. Launch languages are English, Luganda, Runyankole and Swahili, with more added as labelled data grows.

| Stage | Runs on | Model | Job |
| --- | --- | --- | --- |
| Fast screen | Phone, offline | Small multilingual classifier (AfroXLMR-class), quantised | Score every message in milliseconds; pass on anything uncertain or risky |
| Language and slang | Cloud | Language ID + Kuuma slang lexicon | Split code-switched text, expand slang, emoji codes and misspellings |
| Local-language understanding | Cloud | [Sunflower](https://salt.sunbird.ai/models/sunflower-14b/) (Sunbird AI) | Translate and interpret Luganda, Runyankole and other Ugandan languages |
| Context and severity | Cloud | General frontier LLM | Judge intent, severity, category, grooming stage and offender links |
| Advice | Cloud, cached on phone | Frontier LLM + Sunflower | Write guidance in the victim's language and reading level |
| Voice notes | Cloud | Sunflower-Speech | Transcribe abusive voice notes so they can be classified and filed |

Sunflower was built by Kampala's Sunbird AI and covers 31 Ugandan languages plus English. It is open-weight under Apache 2.0, with a hosted API at api.sunbird.ai ([Sunbird SALT](https://salt.sunbird.ai/models/sunflower-14b/)). Its speech variant handles audio in 10 Ugandan languages ([Hugging Face](https://huggingface.co/Sunbird/Sunflower-Speech-Ultravox-32B)).

Design choices that matter for accuracy:

- **Code-switching first.** Most real messages mix languages in one sentence, such as Luganda with English, or Kampala's Luyaaye slang. The model reads the whole message, not word by word.
- **A living slang lexicon.** Native-speaker moderators maintain a list of local insults, coded words and abusive emoji use. It updates the classifier without a full retrain.
- **Banter versus abuse.** Friends insult each other playfully. Kuuma weighs relationship history (how often they talk, whether the tone is two-way) to cut false alarms.
- **Original always kept.** Translations support the analysis and the case file. The original text is the evidence, and every translation is labelled as machine-made.
- **A Ugandan dataset.** Kuuma will build a labelled cyberbullying dataset with partner schools and universities, annotated by native speakers. This becomes a lasting asset for the project.

## System architecture and tech stack

Kuuma screens every message on the phone and sends only uncertain or high-risk messages to the cloud. This keeps most private conversations on the device and keeps working without data.

&#91;embedded content: system architecture · phone and cloud\]

The vault sends only hashes to the case service for trusted timestamps. Full case files are assembled when the victim chooses to share.

| Layer | Choice | Why |
| --- | --- | --- |
| Android services | Kotlin: NotificationListenerService, InputMethodService (keyboard), AccessibilityService | System-level access that cross-platform frameworks cannot provide |
| App UI | Flutter, using platform channels to the Kotlin services | One codebase for victim and guardian apps; iOS later |
| On-device model | TensorFlow Lite or ONNX Runtime, quantised multilingual classifier | Offline, fast and private |
| Local storage | SQLCipher database, Android Keystore signing keys | Encrypted vault with hardware-backed signatures |
| Backend | Python FastAPI, LangChain for LLM orchestration | Async APIs and the team's Python strength |
| Models | Sunbird Sunflower API or self-hosted weights, plus a frontier LLM API | Local-language understanding plus strong reasoning |
| Data | PostgreSQL, S3-compatible object storage with encryption | Cases, profiles and exported bundles |
| Alerts | Firebase Cloud Messaging, Africa's Talking SMS | Push for smartphones, SMS for basic phones |
| Integrity | SHA-256 hash chain, RFC 3161 trusted timestamps | Tamper-evident, court-friendly evidence |
| Web portals | Next.js | Guardian dashboard, verification page, later investigator portal |
| Hosting | AWS (Cape Town region) or Huawei Cloud, Docker | Low latency to Uganda, room to scale |

iOS does not let apps read other apps' notifications. On iOS, Kuuma would rely on the keyboard, a share-to-Kuuma action and screenshot scanning. This is why Android comes first.

## Privacy, consent, safety and legal alignment

Kuuma reads private messages, so it must be designed so it cannot become a spying tool. The victim owns the data, and nothing leaves the phone without their consent.

### Privacy and consent

- Adults install Kuuma themselves. For children, the parent installs it on the child's phone and sets the age bracket. No one can install it remotely, and guardians cannot read the inbox.
- Consent is given once at setup; after that Kuuma runs silently in the background. Each source app is opted in individually.
- Guardians see only flagged items above the agreed severity, never ordinary chats.
- Children are set up by a parent through the linked parent and child apps, and the age bracket sets what the parent sees.
- Data is minimised. Unflagged messages are never stored, and only uncertain messages go to the cloud, stripped of contact details where possible.
- Users can export or permanently delete their vault at any time.
- Kuuma will register with Uganda's Personal Data Protection Office and follow the Data Protection and Privacy Act, 2019, including parental consent for children's data.

### Safety rules

- **No child sexual abuse material.** Kuuma never copies, uploads or displays sexual images of minors. It records that such an image was received, with a hash and metadata, and directs the family to the police and Sauti 116.
- **No automatic police reports.** A person always decides when a case is shared.
- **Human confirmation.** Offender links and grooming-stage findings are suggestions until a person confirms them.
- **Anti-abuse controls.** Kuuma watches for signs it is being used for control, such as a partner setting it up on someone else's phone, and adults can only be protected with their own consent.

### Legal alignment (Uganda)

The legal mapping in case files must track recent court rulings.

- On 17 March 2026, the Constitutional Court declared the Computer Misuse (Amendment) Act, 2022 void. It struck down sections 11, 23, 26, 27, 28 and 29 of the Computer Misuse Act, plus criminal libel in the Penal Code ([Nile Post via allAfrica](https://allafrica.com/stories/202603180565.html)).
- Section 25 ("offensive communication") had already been struck down in January 2023 ([CIPESA](https://cipesa.org/tag/freedom-of-expression/page/30)).
- Section 24, cyber harassment, was earlier upheld by the court and is not among the provisions struck down in 2026 ([Daily Monitor](https://www.monitor.co.ug/uganda/magazines/people-power/court-maintained-cyber-harassment-offence-computer-misuse-act-4172936)). It is the main offence Kuuma's reports will reference.
- Other relevant law includes the Children Act and the Data Protection and Privacy Act, 2019.

Open question: a Ugandan lawyer should review the offence mapping, the evidence format and the admissibility of Kuuma's sealed exhibits before launch.

## Target users, stakeholders and impact

The first users are secondary school and university students in Uganda, the group most active on WhatsApp, TikTok and Instagram and least likely to report abuse.

| Who | Role in Kuuma | What they gain |
| --- | --- | --- |
| Victims (12+ and adults) | Primary users | Early warning, guidance, and evidence they did not have to collect themselves |
| Parents and guardians | Receive alerts | Know when to step in, without reading their child's private chats |
| Schools and universities | Receive case files; pilot partners | Fair, evidence-based disciplinary decisions |
| Uganda Police cybercrime unit | Receives case files | Organised, verifiable evidence that saves investigation time |
| Platforms (Meta, TikTok, X) | Receive reports | Better-documented reports in local languages |
| NGOs and helplines (e.g. Sauti 116, child protection groups) | Referral partners | A route from detection to counselling and legal support |

Expected impact:

- More incidents reported, because evidence is ready and the victim is not starting from nothing.
- Faster intervention, because guardians hear about high-risk messages within minutes, not weeks.
- Fewer cases dropped for lack of evidence.
- A first labelled dataset of cyberbullying in Ugandan languages, useful well beyond Kuuma.

## MVP scope and roadmap

The MVP is an Android app that detects abuse on WhatsApp and SMS in English and Luganda, advises the victim, alerts a guardian and produces a sealed case file. Each later phase deepens the investigation work and opens only once its gate is passed.

&#91;embedded content: roadmap · 3 phases, 2 gates\]

The gates are the decision points. The MVP does not reach real users until a lawyer has reviewed the evidence format and detection meets an agreed accuracy bar. Phase 3 starts only after a school pilot shows the case files are used.

Open question: the timeline for each phase and the pilot school still need to be set.

## Risks, mitigations and success metrics

The biggest risks are wrong flags, misuse as spyware, and Google Play restrictions on the system permissions Kuuma needs.

| Risk | Mitigation |
| --- | --- |
| False positives on banter or false negatives on new slang | Relationship-aware scoring, a living slang lexicon, a "not abuse" button that feeds retraining |
| App used to spy on a partner or child | Consent given at setup on the device itself, flagged items only, no remote install |
| Google Play rejects accessibility or notification access | Clear in-app disclosure and a policy declaration, accessibility made optional, side-load build for pilots |
| Evidence rejected in court or school hearings | Lawyer review of format, hash chain, trusted timestamps, custody log |
| LLM cost and data use in low-bandwidth areas | On-device first pass, only risky messages sent, cached advice, SMS alerts |
| Mishandling child sexual abuse material | Never stored or viewed; hash and metadata only; police referral |
| Offender retaliation if they learn of reporting | Shares are private and victim-controlled; safety plan shown before any report |

Proposed success metrics for the pilot:

- Detection recall and precision per language, with a separate bar for high-severity messages
- Time from a high-severity message to guardian alert
- Share of flagged incidents that turn into a report to a school, platform or police
- Share of reported cases where the case file was accepted and used
- Victim-reported feeling of safety and support, measured before and after the pilot

## Sources

- [Uganda: Constitutional Court Strikes Down Computer Misuse Law — Nile Post via allAfrica, 17 Mar 2026](https://allafrica.com/stories/202603180565.html)
- [Why court maintained cyber harassment offence — Daily Monitor](https://www.monitor.co.ug/uganda/magazines/people-power/court-maintained-cyber-harassment-offence-computer-misuse-act-4172936)
- [A Section of Uganda's Computer Misuse Act Outlawed — CIPESA](https://cipesa.org/tag/freedom-of-expression/page/30)
- [Disrupting Harm in Uganda — ECPAT](https://ecpat.org/?p=12875)
- [Uganda to develop a National Action Plan on online child sexual abuse — UNICEF Uganda](https://www.unicef.org/uganda/stories/uganda-develop-national-action-plan-online-child-sexual-abuse-and-exploitation)
- [200,000 children abused online — The Independent](https://independent.co.ug/200000-children-abused-online)
- [Disrupting Harm key findings — Hugh James](https://www.hughjames.com/?p=10621)
- [More than a third of young people report online bullying — PML Daily](https://pmldaily.com/news/2019/09/more-than-a-third-of-young-people-in-the-world-report-being-victims-of-online-bullying.html)
- [Sunflower-14B — Sunbird AI SALT](https://salt.sunbird.ai/models/sunflower-14b/)
- [Sunflower-Speech — Hugging Face](https://huggingface.co/Sunbird/Sunflower-Speech-Ultravox-32B)
