# Appointment/Booking Flow Fixes - Summary

## Problem Statement
The appointment/booking flow had several issues:
1. Greeting message was combined with the first question instead of being sent separately
2. Flow didn't use the contact's stored name for personalization
3. Flow asked for information already available in the database (name, email, phone)
4. Contact information captured in the flow wasn't saved back to the database

## Solutions Implemented

### 1. Separate Greeting State
**File:** `FlowDefinitionLoader.java`
- Created a separate "GREETING" state that displays before any questions
- Greeting auto-advances to the first question (no user input required)
- Flow now: GREETING → STATE_0 → STATE_1 → ... → COMPLETE

### 2. Personalized Greetings
**File:** `StateResolver.java`
- Added `personalizeMessage()` method
- Supports placeholders: `{{contact.name}}` and `{{contact.firstName}}`
- Example: "Hello {{contact.firstName}}!" becomes "Hello John!"
- Falls back to "Hello there!" if name not available

### 3. Smart Field Auto-Fill
**File:** `FlowStateMachine.java`
- Added `getExistingFieldValue()` method
- Checks Contact entity for existing name, email, phone before asking
- Auto-fills known values and skips to next question
- Only asks for information that's actually missing

### 4. Contact Information Persistence
**Files:** `AppointmentFlowHandler.java`, `BookingFlowHandler.java`
- Added logic to save captured name and email back to Contact entity
- Ensures future flows can auto-fill these fields
- Added ContactRepository dependency to both handlers

## Technical Changes

### Modified Files
1. `src/main/java/com/chatcrmlite/backend/services/flow/FlowDefinitionLoader.java`
   - Modified `buildMachineDefFromSteps()` method

2. `src/main/java/com/chatcrmlite/backend/services/flow/StateResolver.java`
   - Added `personalizeMessage()` method
   - Modified `sendStateMessage()` to use personalization

3. `src/main/java/com/chatcrmlite/backend/services/flow/FlowStateMachine.java`
   - Modified `executeState()` to check and auto-fill known fields
   - Added `getExistingFieldValue()` helper method

4. `src/main/java/com/chatcrmlite/backend/flow/AppointmentFlowHandler.java`
   - Added imports for Contact and ContactRepository
   - Added contact update logic in handle() method

5. `src/main/java/com/chatcrmlite/backend/flow/BookingFlowHandler.java`
   - Added imports for Contact and ContactRepository
   - Added contact update logic in handle() method

## How to Use Personalization

In your greeting message configuration (via UI or database), use these placeholders:

```
Hello {{contact.firstName}}! 👋

Thank you for choosing to book an appointment with us.
```

If the contact's name is "John Doe", this will be sent as:
```
Hello John! 👋

Thank you for choosing to book an appointment with us.
```

## Flow Behavior Examples

### Scenario 1: New Contact (First Time)
- Sends greeting: "Hello there! 👋 ..."
- Asks for: Name → Phone → Email → Other enabled fields
- Saves all information to database

### Scenario 2: Returning Contact with Name
- Sends greeting: "Hello John! 👋 ..."
- Skips: Name (already stored)
- Asks for: Phone → Email → Other enabled fields
- Updates database with new information

### Scenario 3: Returning Contact with Name + Email
- Sends greeting: "Hello John! 👋 ..."
- Skips: Name, Email (already stored)
- Asks for: Phone → Other enabled fields
- Only stores newly collected data

## Database Configuration

The flow respects the database configuration in `tenant_flow_config` table:
- Fields marked as `enabled: true` are included
- Fields marked as `enabled: false` are skipped entirely
- `defaultEnabled` in `master-fields.json` sets the initial state
- Tenants can customize via the UI settings

## Testing

To test the fixes:

1. **Test Greeting Separation:**
   - Start an appointment flow
   - Verify greeting is sent as a separate message
   - Verify first question comes immediately after

2. **Test Personalization:**
   - Use a contact with a known name
   - Start appointment flow
   - Verify greeting uses the contact's name

3. **Test Auto-Fill:**
   - Use a contact with name/email already stored
   - Start appointment flow
   - Verify those fields are skipped

4. **Test Contact Update:**
   - Complete a flow with new name/email
   - Check database to confirm information is saved
   - Start another flow to verify auto-fill works

## Compatibility Notes

- All changes are backward compatible
- Existing flows without greeting messages work as before
- Greeting placeholders are optional ({{contact.name}} and {{contact.firstName}})
- If contact data is not available, placeholders default to "there"

## Build Status
✅ Successfully compiled with `mvn clean compile -DskipTests`

## Next Steps

1. Deploy to test environment
2. Test with tenant email: gyanvaniai@gmail.com
3. Verify greeting personalization works
4. Verify auto-fill skips known fields
5. Verify contact information is saved properly
