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
        if (val.length > 255) return 'Name must not exceed 255 characters.';
    }

    if (fieldType === 'EMAIL' || lowerKey === 'email') {
        if (val !== '') {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!emailRegex.test(val)) return 'Please enter a valid email address.';
            if (val.length > 255) return 'Email must not exceed 255 characters.';
        }
    }

    if (fieldType === 'PHONE' || lowerKey === 'phone' || lowerKey === 'mobile') {
        if (val !== '') {
            const digits = val.replace(/\D/g, '');
            if (digits.length < 10 || digits.length > 15) {
                return 'Phone number must contain between 10 and 15 digits.';
            }
            if (/^(\d)\1+$/.test(digits)) {
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
