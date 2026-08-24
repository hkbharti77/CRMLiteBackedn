/**
 * CRM Chat Widget - Input Validation
 */

export function validateFieldInput(fieldType, key, value) {
    if (value === undefined || value === null) return null;
    const val = String(value).trim();
    const lowerKey = (key || '').toLowerCase();
    const isNameField = lowerKey === 'name' || lowerKey.endsWith('_name') || lowerKey.startsWith('name_');

    if (isNameField) {
        if (val.length < 2) return 'Name must be at least 2 characters long.';
        if (val.length > 66) return 'Name must not exceed 66 characters.';
        const nameRegex = /^[a-zA-Z\s'.,-]+$/;
        if (!nameRegex.test(val)) return 'Name can only contain letters, spaces, and basic punctuation.';
    }

    if (fieldType === 'EMAIL' || lowerKey === 'email') {
        if (val !== '') {
            if (val.length > 156) return 'Email must not exceed 156 characters.';
            const emailRegex = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
            if (!emailRegex.test(val)) return 'Please enter a valid email address.';
        }
    }

    if (fieldType === 'PHONE' || lowerKey === 'phone' || lowerKey === 'mobile') {
        if (val !== '') {
            // Strip country code prefix (e.g., +91) to get local digits
            let localVal = val;
            if (localVal.startsWith('+')) {
                // Remove the + and up to 4 digits of country code
                localVal = localVal.replace(/^\+\d{1,4}/, '');
            }
            const digits = localVal.replace(/\D/g, '');
            if (digits.length < 7 || digits.length > 15) {
                return 'Phone number must be between 7 and 15 digits.';
            }
            const allDigits = val.replace(/\D/g, '');
            if (/^(\d)\1+$/.test(allDigits)) {
                return 'Please enter a valid active phone number.';
            }
            const phoneRegex = /^\+?[0-9\s\-()]+$/;
            if (!phoneRegex.test(val)) {
                return 'Please enter a valid phone number format.';
            }
        }
    }

    if (lowerKey === 'subject') {
        if (val.length < 3) return 'Subject must be at least 3 characters long.';
        if (val.length > 255) return 'Subject must not exceed 255 characters.';
    }

    if (lowerKey === 'message') {
        if (val.length < 10) return 'Please describe your issue in more detail (at least 10 characters).';
        if (val.length > 5000) return 'Message must not exceed 5000 characters.';
    }

    return null;
}

export function validateSupportPayload(collectedData) {
    const d = collectedData || {};
    const missing = [];

    if (!d.name || !d.name.trim()) missing.push('name');
    if (!d.email || !d.email.trim()) missing.push('email address');
    if (!d.subject || !d.subject.trim()) missing.push('subject');
    if (!d.message || !d.message.trim()) missing.push('issue description');

    if (missing.length > 0) {
        return {
            isValid: false,
            missing,
            payload: null
        };
    }

    const payload = {
        name: d.name.trim(),
        email: d.email.trim(),
        subject: d.subject.trim(),
        message: d.message.trim(),
        phone: (d.phone && d.phone.trim()) ? d.phone.trim() : null,
        category: (d.category && d.category.trim()) ? d.category.trim() : null
    };

    return {
        isValid: true,
        missing: [],
        payload
    };
}
