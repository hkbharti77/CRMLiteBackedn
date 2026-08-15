# Appointment/Booking Flow Diagram

## Before Fix

```
User clicks "Book Appointment"
          ↓
[Combined Greeting + First Question]
"Hello! Thank you for booking. 👋

👋 What is your full name?"
          ↓
Wait for user input (name)
          ↓
Ask for phone (even if already stored)
          ↓
Ask for email (even if already stored)
          ↓
Ask other enabled fields
          ↓
Create appointment
          ↓
Send confirmation
(Contact info NOT saved to DB)
```

## After Fix

```
User clicks "Book Appointment"
          ↓
[GREETING STATE - Auto-advances]
"Hello John! 👋

Thank you for choosing to book 
an appointment with us."
          ↓
[STATE_0 - Name Field]
Check DB: Name already exists?
  ├─ YES → Skip, auto-fill
  └─ NO  → Ask "👋 What is your full name?"
          ↓
[STATE_1 - Phone Field]
Check DB: Phone already exists?
  ├─ YES → Skip, auto-fill
  └─ NO  → Ask "📱 Please provide your contact number"
          ↓
[STATE_2 - Email Field]
Check DB: Email already exists?
  ├─ YES → Skip, auto-fill
  └─ NO  → Ask "📩 What is your email address?"
          ↓
[Other enabled fields based on niche]
          ↓
Save captured name/email to Contact entity
          ↓
Create appointment with all data
          ↓
Send personalized confirmation
```

## Flow State Machine

### State Structure (After Fix)

```
┌──────────────────────────────────────────────────┐
│ GREETING (StateDef)                              │
│ - type: MESSAGE                                  │
│ - text: "Hello {{contact.firstName}}! ..."      │
│ - saveInputAs: null (no input capture)          │
│ - transitions: [→ STATE_0]                       │
│ - Auto-advances immediately                     │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ STATE_0 (StateDef)                               │
│ - type: MESSAGE                                  │
│ - text: "👋 What is your full name?"            │
│ - saveInputAs: "name"                            │
│ - transitions: [→ STATE_1]                       │
│ - Pre-check: Contact.name exists? → Skip        │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ STATE_1 (StateDef)                               │
│ - type: MESSAGE                                  │
│ - text: "📱 Please provide your contact number" │
│ - saveInputAs: "phone"                           │
│ - transitions: [→ STATE_2]                       │
│ - Pre-check: Contact.phone exists? → Skip       │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ STATE_2 (StateDef)                               │
│ - type: MESSAGE                                  │
│ - text: "📩 What is your email address?"        │
│ - saveInputAs: "email"                           │
│ - transitions: [→ STATE_3 or COMPLETE]          │
│ - Pre-check: Contact.email exists? → Skip       │
└──────────────────────────────────────────────────┘
                    ↓
┌──────────────────────────────────────────────────┐
│ COMPLETE (StateDef)                              │
│ - type: END                                      │
│ - Triggers: AppointmentFlowHandler.handle()     │
│ - Saves contact info to DB                      │
│ - Creates appointment                            │
│ - Sends confirmation                             │
└──────────────────────────────────────────────────┘
```

## Data Flow

### Contact Entity Update Flow

```
FlowStateMachine.completeFlow()
          ↓
AppointmentFlowHandler.handle(context)
          ↓
Extract data: context.getCollectedData()
          ↓
┌─────────────────────────────────────┐
│ Check if name was captured          │
│   if (data.get("name") != null)     │
│      AND Contact.name is blank      │
│   → contact.setName(...)            │
│   → contactRepository.save(contact) │
└─────────────────────────────────────┘
          ↓
┌─────────────────────────────────────┐
│ Check if email was captured         │
│   if (data.get("email") != null)    │
│      AND Contact.email is blank     │
│   → contact.setEmail(...)           │
│   → contactRepository.save(contact) │
└─────────────────────────────────────┘
          ↓
Create Appointment with all flow data
          ↓
Return confirmation message
```

## Field Enablement Logic

```
master-fields.json
    ↓
Each field has: defaultEnabled (true/false)
    ↓
┌──────────────────────────────────────┐
│ Database Override (Optional)         │
│ tenant_flow_config table             │
│ - Tenant can enable/disable fields   │
│ - Tenant can reorder fields          │
│ - Tenant can customize greeting      │
└──────────────────────────────────────┘
    ↓
FlowConfigService.applyTenantConfiguration()
    ↓
Filter fields:
  - If DB config exists: Use DB settings
  - Else: Use defaultEnabled from JSON
  - Apply applicableNiches filter
    ↓
Build FlowMachineDef with filtered fields
    ↓
Execute flow with only enabled fields
```

## Personalization Process

```
StateResolver.sendStateMessage(stateDef, contact, ...)
          ↓
Extract message text: stateDef.getText()
          ↓
personalizeMessage(text, contact)
          ↓
┌─────────────────────────────────────────────┐
│ Replace {{contact.name}}                    │
│   - If contact.name exists: "John Doe"      │
│   - Else: "there"                           │
├─────────────────────────────────────────────┤
│ Replace {{contact.firstName}}               │
│   - If contact.name exists: "John"          │
│   - Else: "there"                           │
└─────────────────────────────────────────────┘
          ↓
Send personalized message to WhatsApp
```

## Auto-Fill Decision Tree

```
executeState(state, machineDef, contact, owner)
          ↓
Is this a MESSAGE state with saveInputAs?
  ├─ NO  → Send message normally
  └─ YES → Check for existing value
          ↓
getExistingFieldValue(contact, fieldKey)
          ↓
    fieldKey == "name"?
      ├─ contact.name exists && !startsWith("WhatsApp User")
      │    → Return contact.name
      └─ Else → Return null
          ↓
    fieldKey == "email"?
      ├─ contact.email exists && !isBlank
      │    → Return contact.email
      └─ Else → Return null
          ↓
    fieldKey == "phone"?
      ├─ contact.phone exists && !isBlank
      │    → Return contact.phone
      └─ Else → Return null
          ↓
If existing value found:
  ├─ saveAnswer(state, fieldKey, existingValue)
  ├─ Log: "Auto-filling field..."
  └─ transitionToState(nextState) [SKIP QUESTION]
          ↓
Else:
  └─ Send question to user [ASK NORMALLY]
```

## Example Scenarios

### Scenario: First-Time User

```
Contact DB: {name: "WhatsApp User 123", email: null, phone: "919876543210"}
          ↓
Flow starts:
  1. GREETING: "Hello there! 👋 ..." [Auto-advance]
  2. STATE_0: "What is your full name?" [ASK - name invalid]
     User: "John Doe"
  3. STATE_1: "Contact number?" [SKIP - phone exists]
     Auto-fill: "919876543210"
  4. STATE_2: "Email address?" [ASK - email missing]
     User: "john@example.com"
  5. COMPLETE: Save name + email to Contact, create appointment
```

### Scenario: Returning User

```
Contact DB: {name: "John Doe", email: "john@example.com", phone: "919876543210"}
          ↓
Flow starts:
  1. GREETING: "Hello John! 👋 ..." [Auto-advance]
  2. STATE_0: [SKIP - name exists, auto-fill "John Doe"]
  3. STATE_1: [SKIP - phone exists, auto-fill "919876543210"]
  4. STATE_2: [SKIP - email exists, auto-fill "john@example.com"]
  5. Other niche-specific fields (if enabled)
  6. COMPLETE: Create appointment with all data
```

This shows how returning users with complete info only see the greeting and niche-specific questions, making the experience much faster!
