package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.livechat.LiveChatAssignment;
import com.chatcrmlite.backend.repositories.LiveChatAssignmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LiveChatAuthorizationService {

    @Autowired
    private LiveChatAssignmentRepository assignmentRepository;

    public boolean canAccessContact(Contact contact, User user) {
        if (contact == null || user == null) return false;
        if (!isSameTenant(contact, user)) return false;

        // ADMIN, OWNER, SUPER_ADMIN can view all contacts
        if (isAdminOrOwner(user)) return true;

        // AGENT can view if unassigned, or if assigned to themselves
        if (contact.getAssignedAgent() == null) return true;
        return contact.getAssignedAgent().getId().equals(user.getId());
    }

    public boolean canSendMessage(Contact contact, User user) {
        if (contact == null || user == null) return false;
        if (!isSameTenant(contact, user)) return false;

        if (isAdminOrOwner(user)) return true;

        // Agent can only send messages if assigned to them or unassigned
        return contact.getAssignedAgent() == null || contact.getAssignedAgent().getId().equals(user.getId());
    }

    public boolean canTakeover(Contact contact, User user) {
        if (contact == null || user == null) return false;
        if (!isSameTenant(contact, user)) return false;

        // Only Admin, Owner, Super Admin can takeover
        return isAdminOrOwner(user);
    }

    public boolean canTransfer(Contact contact, User user) {
        if (contact == null || user == null) return false;
        if (!isSameTenant(contact, user)) return false;

        if (isAdminOrOwner(user)) return true;

        // Agent can transfer if assigned to them
        return contact.getAssignedAgent() != null && contact.getAssignedAgent().getId().equals(user.getId());
    }

    public boolean canResolve(Contact contact, User user) {
        if (contact == null || user == null) return false;
        if (!isSameTenant(contact, user)) return false;

        if (isAdminOrOwner(user)) return true;

        return contact.getAssignedAgent() != null && contact.getAssignedAgent().getId().equals(user.getId());
    }

    public boolean isAdminOrOwner(User user) {
        if (user == null || user.getRole() == null) return false;
        User.Role role = user.getRole();
        return role == User.Role.ADMIN || role == User.Role.OWNER || role == User.Role.SUPER_ADMIN;
    }

    private boolean isSameTenant(Contact contact, User user) {
        if (contact.getTenant() != null && user.getTenant() != null) {
            return contact.getTenant().getId().equals(user.getTenant().getId());
        }
        if (contact.getOwner() != null && user.getTenant() != null) {
            return contact.getOwner().getTenant().getId().equals(user.getTenant().getId());
        }
        return true;
    }
}
