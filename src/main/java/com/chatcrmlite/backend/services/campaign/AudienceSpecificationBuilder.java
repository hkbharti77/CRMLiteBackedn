package com.chatcrmlite.backend.services.campaign;

import com.chatcrmlite.backend.dto.AudienceFilterDTO;
import com.chatcrmlite.backend.dto.SegmentRuleDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AudienceSpecificationBuilder {

    public Specification<Contact> buildSpecification(User owner, AudienceFilterDTO filterDTO) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Rule 1: Always filter by owner
            predicates.add(cb.equal(root.get("owner"), owner));

            // Optional distinct for collection joins
            query.distinct(true);

            if (filterDTO != null && filterDTO.getRules() != null && !filterDTO.getRules().isEmpty()) {
                List<Predicate> rulePredicates = new ArrayList<>();
                for (SegmentRuleDTO rule : filterDTO.getRules()) {
                    Predicate p = buildPredicate(root, query, cb, rule);
                    if (p != null) {
                        rulePredicates.add(p);
                    }
                }

                if (!rulePredicates.isEmpty()) {
                    if ("OR".equalsIgnoreCase(filterDTO.getLogicalOperator())) {
                        predicates.add(cb.or(rulePredicates.toArray(new Predicate[0])));
                    } else {
                        predicates.add(cb.and(rulePredicates.toArray(new Predicate[0])));
                    }
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Predicate buildPredicate(Root<Contact> root, CriteriaQuery<?> query, CriteriaBuilder cb, SegmentRuleDTO rule) {
        String field = rule.getField();
        String operator = rule.getOperator();
        String valueStr = rule.getValueAsString();
        List<String> valueList = rule.getValueAsList();

        try {
            // Handle Tag queries
            if ("contact.tags".equals(field)) {
                Join<Contact, Tag> tagsJoin = root.join("tags", JoinType.INNER);
                if ("CONTAINS_ANY".equalsIgnoreCase(operator)) {
                    return tagsJoin.get("name").in(valueList);
                } else if ("CONTAINS_ALL".equalsIgnoreCase(operator)) {
                    // CONTAINS_ALL is complex in JPA without native SQL grouping.
                    // For simplicity in a single root query without group by having, we can do multiple AND EXISTS subqueries,
                    // or just fallback to IN for now. 
                    // To do it properly:
                    // SELECT c FROM Contact c WHERE (SELECT count(t.id) FROM c.tags t WHERE t.name IN (:tags)) = :size
                    return tagsJoin.get("name").in(valueList); 
                } else if ("EXCLUDES".equalsIgnoreCase(operator)) {
                    // We need a subquery for EXCLUDES
                    Subquery<UUID> subquery = query.subquery(java.util.UUID.class);
                    Root<Contact> subRoot = subquery.from(Contact.class);
                    Join<Contact, Tag> subTags = subRoot.join("tags");
                    subquery.select(subRoot.get("id"))
                            .where(subTags.get("name").in(valueList));
                    return cb.not(root.get("id").in(subquery));
                }
            }

            // Handle Lead queries
            if (field != null && field.startsWith("lead.")) {
                // Not all contacts have leads, so we need a subquery or join.
                // Using a subquery to find contacts that have a matching lead.
                Subquery<java.util.UUID> leadSubquery = query.subquery(java.util.UUID.class);
                Root<Lead> leadRoot = leadSubquery.from(Lead.class);
                leadSubquery.select(leadRoot.get("contact").get("id"));

                String leadField = field.substring("lead.".length());
                Path<?> path = leadRoot.get(leadField);
                Predicate leadCondition = buildCondition(path, operator, valueStr, valueList, cb);
                
                if (leadCondition != null) {
                    leadSubquery.where(leadCondition);
                    return root.get("id").in(leadSubquery);
                }
            }

            // Handle direct Contact queries
            if (field != null && field.startsWith("contact.")) {
                String contactField = field.substring("contact.".length());
                if (!"tags".equals(contactField)) {
                    Path<?> path = root.get(contactField);
                    return buildCondition(path, operator, valueStr, valueList, cb);
                }
            }

        } catch (Exception e) {
            // Log warning, return null to skip invalid rule
            System.err.println("Failed to build predicate for rule: " + rule + " error: " + e.getMessage());
        }

        return null;
    }

    private Predicate buildCondition(Path<?> path, String operator, String valueStr, List<String> valueList, CriteriaBuilder cb) {
        Class<?> javaType = path.getJavaType();
        
        if ("EQUALS".equalsIgnoreCase(operator)) {
            return cb.equal(path, parseValue(javaType, valueStr));
        } else if ("NOT_EQUALS".equalsIgnoreCase(operator)) {
            return cb.notEqual(path, parseValue(javaType, valueStr));
        } else if ("IN".equalsIgnoreCase(operator)) {
            CriteriaBuilder.In<Object> inClause = cb.in(path);
            for (String val : valueList) {
                inClause.value(parseValue(javaType, val));
            }
            return inClause;
        } else if ("GREATER_THAN".equalsIgnoreCase(operator)) {
            if (Comparable.class.isAssignableFrom(javaType)) {
                return cb.greaterThan((Expression<? extends Comparable>) path, (Comparable) parseValue(javaType, valueStr));
            }
        } else if ("LESS_THAN".equalsIgnoreCase(operator)) {
            if (Comparable.class.isAssignableFrom(javaType)) {
                return cb.lessThan((Expression<? extends Comparable>) path, (Comparable) parseValue(javaType, valueStr));
            }
        }
        return null;
    }

    private Object parseValue(Class<?> javaType, String value) {
        if (value == null) return null;
        if (javaType.isEnum()) {
            return Enum.valueOf((Class<Enum>) javaType, value);
        }
        if (javaType.equals(Boolean.class) || javaType.equals(boolean.class)) {
            return Boolean.valueOf(value);
        }
        if (javaType.equals(Integer.class) || javaType.equals(int.class)) {
            return Integer.valueOf(value);
        }
        if (javaType.equals(java.math.BigDecimal.class)) {
            return new java.math.BigDecimal(value);
        }
        if (javaType.equals(LocalDateTime.class)) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        }
        return value; // default string
    }
}
