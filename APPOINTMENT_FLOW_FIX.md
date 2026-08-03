# Appointment/Booking Flow Improvements

## Issues Fixed

### 1. **Greeting Message Not Shown Separately**
**Problem:** The greeting message was being prepended to the first question, making it appear as one long message instead of a welcoming greeting followed by the first question.

**Solution:** 
- Modified `FlowDefinitionLoader.buildMachineDefFromSteps()` to create a separate "GREETING" state
- The greeting state auto-advances to the first question state
- Greeting is now sent as a standalone message before any questions are asked

### 2. **Greeting Not Personalized with Contact Name**
**Problem:** Even when the contact's name was already stored in the database, the greeting would not use it.

**Solution:**
- Added `personalizeMessage()` method in `StateResolver` 
- Supports placeholders: `{{contact.name}}` and `{{contact.firstName}}`
- Example: "Hello {{contact.firstName}}! 👋" becomes "Hello John! 👋"

### 3. **Asking for Information Already in Database**
**Problem:** The flow would ask for name, email, and phone even if they were already stored in the Contact entity.

**Solution:**
- Added `getExistingFieldValue()` method in `FlowStateMachine`
- Before sending a question, checks if the field (name, email, phone) already exists
- Auto-fills known values and skips to the next question
- Only asks for information that's actually missing

### 4. **Contact Information Not Updated After Flow**
**Problem:** When users provided name/email in the flow, it wasn't saved back to the Contact entity.

**Solution:**
- Updated `AppointmentFlowHandler` to save captured name and email to Contact
- Updated `BookingFlowHandler` to save captured name and email to Contact
- Contact information is now persisted for future use

## How It Works Now

### Flow Sequence (Example: Appointment Booking)

1. **User clicks "Book Appointment"**
2. **Greeting State (NEW)**
   - System sends: "Hello John! 👋 Thank you for choosing to book an appointment with us."
   - Auto-advances immediately (no user input required)

3. **Name Field**
   - If name already exists in DB: **SKIPPED** (auto-filled)
   - If name missing: Asks "👋 What is your full name?"

4. **Phone Field**
   - If phone already exists: **SKIPPED** (auto-filled)
   - If phone missing: Asks "📱 Please provide your contact number:"

5. **Email Field (if enabled)**
   - If email already exists: **SKIPPED** (auto-filled)
   - If email missing: Asks "📩 What is your email address?"

6. **Other Enabled Fields**
   - Only asks for fields that are `defaultEnabled: true` or manually enabled in DB
   - Fields filtered by `applicableNiches` if specified

7. **Completion**
   - Contact information updated in database
   - Appointment created
   - Confirmation message sent

## Configuration

### Master Fields (`master-fields.json`)
- Each field has a `defaultEnabled` flag
- Only fields with `defaultEnabled: true` are included in the flow by default
- Tenant can override in the database to enable/disable specific fields

### Database Configuration (`tenant_flow_config` table)
- Stores tenant-specific field configuration
- Includes greeting message customization
- Field order and enablement can be customized per tenant

### Greeting Message Personalization
In the tenant's flow configuration, use these placeholders:
- `{{contact.name}}` - Full name (e.g., "John Doe")
- `{{contact.firstName}}` - First name only (e.g., "John")

Example greeting:
```
Hello {{contact.firstName}}! 👋

Thank you for choosing to book an appointment with us. Let's gather a few details to confirm your booking.
```

## Technical Changes

### Files Modified

1. **FlowDefinitionLoader.java**
   - Changed `buildMachineDefFromSteps()` to create separate GREETING state
   - Greeting now sent independently from first question

2. **StateResolver.java**
   - Added `personalizeMessage()` method for placeholder replacement
   - Personalizes messages with contact name before sending

3. **FlowStateMachine.java**
   - Added `getExistingFieldValue()` to retrieve known contact fields
   - Modified `executeState()` to auto-fill and skip known fields
   - Auto-advances through greeting state (no input required)

4. **AppointmentFlowHandler.java**
   - Added contact update logic to save name and email
   - Added `ContactRepository` dependency

5. **BookingFlowHandler.java**
   - Added contact update logic to save name and email
   - Added `ContactRepository` dependency

## Testing Checklist

- [ ] First-time user: Should see greeting + all enabled questions
- [ ] Returning user with name: Should see personalized greeting, skip name field
- [ ] Returning user with name+email: Should skip both name and email
- [ ] Greeting message displays separately before first question
- [ ] Contact name and email saved after flow completion
- [ ] Niche-specific fields only shown for applicable niches
- [ ] Fields with `defaultEnabled: false` are not shown unless explicitly enabled

## Database Query to Check Configuration

```sql
-- Check tenant flow configuration
SELECT 
    u.email as tenant_email,
    tfc.flow_type,
    tfc.configuration_json
FROM tenant_flow_config tfc
JOIN users u ON u.id = tfc.tenant_id
WHERE u.email = 'gyanvaniai@gmail.com';

-- Check contact information
SELECT 
    c.wa_id,
    c.name,
    c.email,
    c.phone
FROM contacts c
JOIN users u ON u.id = c.owner_id
WHERE u.email = 'gyanvaniai@gmail.com';
```

## Future Enhancements

1. **Smart Field Confirmation**
   - "I see your name is John Doe. Is that correct? (Yes/Update)"
   - Allows users to update pre-filled information if needed

2. **More Contact Fields**
   - Add more fields to Contact entity (address, city, etc.)
   - Expand auto-fill logic to cover additional fields

3. **Conditional Logic**
   - Skip certain questions based on previous answers
   - Example: If "online consultation" selected, skip "location" field

4. **Historical Data Pre-fill**
   - Use data from previous appointments/bookings
   - "Last time you selected 'Evening slot', use the same? (Yes/Change)"
