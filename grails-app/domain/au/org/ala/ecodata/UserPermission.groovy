package au.org.ala.ecodata

import org.bson.types.ObjectId

/**
 * Domain class to store permissions settings on a User/Project
 * level.
 * @see AccessLevel
 */
class UserPermission {
    ObjectId id
    String userId
    String entityId
    AccessLevel accessLevel
    String entityType

    static constraints = {
        userId(unique: ['accessLevel', 'entityId']) // prevent duplicate entries
    }
}
