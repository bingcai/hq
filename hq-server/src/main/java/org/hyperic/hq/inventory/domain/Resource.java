package org.hyperic.hq.inventory.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.PersistenceContext;
import javax.persistence.Transient;
import javax.persistence.Version;
import javax.validation.constraints.NotNull;

import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.inventory.InvalidRelationshipException;
import org.hyperic.hq.reference.RelationshipTypes;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.TraversalPosition;
import org.neo4j.graphdb.Traverser;
import org.springframework.datastore.annotation.Indexed;
import org.springframework.datastore.graph.annotation.GraphProperty;
import org.springframework.datastore.graph.annotation.NodeEntity;
import org.springframework.datastore.graph.annotation.RelatedTo;
import org.springframework.datastore.graph.api.Direction;
import org.springframework.datastore.graph.neo4j.finder.FinderFactory;
import org.springframework.datastore.graph.neo4j.support.GraphDatabaseContext;
import org.springframework.datastore.graph.neo4j.support.SubReferenceNodeTypeStrategy;
import org.springframework.transaction.annotation.Transactional;

@Entity
@NodeEntity(partial = true)
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public class Resource {

    @Transient
    @ManyToOne
    @RelatedTo(type = "MANAGED_BY", direction = Direction.OUTGOING, elementClass = Agent.class)
    private Agent agent;

    @GraphProperty
    @Transient
    private String description;

    @PersistenceContext
    transient EntityManager entityManager;

    @javax.annotation.Resource
    transient FinderFactory finderFactory;

    @javax.annotation.Resource
    private transient GraphDatabaseContext graphDatabaseContext;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE)
    @Column(name = "id")
    private Integer id;

    @GraphProperty
    @Transient
    private String location;

    @GraphProperty
    @Transient
    private String modifiedBy;

    // TODO do I need Indexed and GraphProperty?
    @NotNull
    @Indexed
    @GraphProperty
    @Transient
    private String name;

    @OneToMany
    @Transient
    @RelatedTo(type = "OWNS", direction = Direction.INCOMING, elementClass = AuthzSubject.class)
    private AuthzSubject owner;

    @RelatedTo(type = "HAS_MEMBER", direction = Direction.INCOMING, elementClass = ResourceGroup.class)
    @OneToMany
    @Transient
    private Set<ResourceGroup> resourceGroups;

    @Transient
    @ManyToOne
    @RelatedTo(type = RelationshipTypes.IS_A, direction = Direction.OUTGOING, elementClass = ResourceType.class)
    private ResourceType type;

    @Version
    @Column(name = "version")
    private Integer version;

    public Resource() {
    }

    public Resource(Node n) {
        setUnderlyingState(n);
    }

    @Transactional
    public void flush() {
        if (this.entityManager == null)
            this.entityManager = entityManager();
        this.entityManager.flush();
    }

    public Agent getAgent() {
        return agent;
    }

    public Config getAutoInventoryConfig() {
        return getConfig("AutoInventory");
    }

    private Config getConfig(String type) {
        Iterable<Relationship> relationships = this.getUnderlyingState().getRelationships(
            DynamicRelationshipType.withName("HAS_CONFIG"), org.neo4j.graphdb.Direction.OUTGOING);
        for (Relationship relationship : relationships) {
            if (type.equals(relationship.getProperty("configType"))) {
                // TODO enforce no more than one?
                return graphDatabaseContext.createEntityFromState(
                    relationship.getOtherNode(getUnderlyingState()), Config.class);
            }
        }
        return null;
    }

    public Config getControlConfig() {
        return getConfig("Control");
    }

    public String getDescription() {
        return description;
    }

    public Integer getId() {
        return this.id;
    }

    public String getLocation() {
        return location;
    }

    public Config getMeasurementConfig() {
        return getConfig("Measurement");
    }

    public String getModifiedBy() {
        return modifiedBy;
    }

    public String getName() {
        return name;
    }

    public AuthzSubject getOwner() {
        return owner;
    }

    public Config getProductConfig() {
        return getConfig("Product");
    }

    public Map<String, Object> getProperties() {
        Map<String, Object> properties = new HashMap<String, Object>();
        for (String key : getUnderlyingState().getPropertyKeys()) {
            try {
                properties.put(key, getProperty(key));
            } catch (IllegalArgumentException e) {
                // filter out the properties we've defined at class-level, like
                // name
            }
        }
        return properties;
    }

    public Object getProperty(String key) {
        PropertyType propertyType = type.getPropertyType(key);
        if (propertyType == null) {
            throw new IllegalArgumentException("Property " + key +
                                               " is not defined for resource of type " +
                                               type.getName());
        }
        return getUnderlyingState().getProperty(key, propertyType.getDefaultValue());
    }

    private Set<Resource> getRelatedResources(String relationName,
                                              org.neo4j.graphdb.Direction direction) {
        Set<Resource> resources = new HashSet<Resource>();
        Traverser relationTraverser = getUnderlyingState().traverse(Traverser.Order.BREADTH_FIRST,
            new StopEvaluator() {
                public boolean isStopNode(TraversalPosition currentPos) {
                    return currentPos.depth() >= 1;
                }
            }, ReturnableEvaluator.ALL_BUT_START_NODE,
            DynamicRelationshipType.withName(relationName), direction);
        for (Node related : relationTraverser) {
            resources.add(graphDatabaseContext.createEntityFromState(related, Resource.class));
        }
        return resources;
    }

    public Set<ResourceRelation> getRelationships() {
        // TODO This is hardcoded for the demo, however should be able to
        // specify direction/relationship name via parameters
        Iterable<Relationship> relationships = getUnderlyingState().getRelationships(
            org.neo4j.graphdb.Direction.OUTGOING);
        Set<ResourceRelation> resourceRelations = new HashSet<ResourceRelation>();
        for (Relationship relationship : relationships) {
            // Don't include Neo4J relationship b/w Node and its Java type
            if (!relationship.isType(SubReferenceNodeTypeStrategy.INSTANCE_OF_RELATIONSHIP_TYPE)) {
                Class<?> otherEndType = graphDatabaseContext.getJavaType(relationship
                    .getOtherNode(getUnderlyingState()));
                if (Resource.class.isAssignableFrom(otherEndType)) {
                    resourceRelations.add(graphDatabaseContext.createEntityFromState(relationship,
                        ResourceRelation.class));
                }
            }
        }
        return resourceRelations;
    }

    private Set<ResourceRelation> getRelationships(String relationName,
                                                   org.neo4j.graphdb.Direction direction) {
        Set<ResourceRelation> resourceRelations = new HashSet<ResourceRelation>();
        Iterable<Relationship> relationships = getUnderlyingState().getRelationships(
            DynamicRelationshipType.withName(relationName), direction);
        for (Relationship relationship : relationships) {
            resourceRelations.add(graphDatabaseContext.createEntityFromState(relationship,
                ResourceRelation.class));
        }
        return resourceRelations;
    }

    public Set<ResourceRelation> getRelationshipsFrom(String relationName) {
        return getRelationships(relationName, org.neo4j.graphdb.Direction.OUTGOING);
    }

    public Set<ResourceRelation> getRelationshipsTo(String relationName) {
        return getRelationships(relationName, org.neo4j.graphdb.Direction.INCOMING);
    }

    public ResourceRelation getRelationshipTo(Resource resource, String relationName) {
        // TODO this doesn't take direction into account
        return (ResourceRelation) getRelationshipTo(resource, ResourceRelation.class, relationName);
    }

    public Resource getResourceFrom(String relationName) {
        // TODO enforce only one?
        return getRelatedResources(relationName, org.neo4j.graphdb.Direction.OUTGOING).iterator()
            .next();
    }

    public Set<ResourceGroup> getResourceGroups() {
        return resourceGroups;
    }

    public Set<Resource> getResourcesFrom(String relationName) {
        return getRelatedResources(relationName, org.neo4j.graphdb.Direction.OUTGOING);
    }

    public Set<Resource> getResourcesTo(String relationName) {
        return getRelatedResources(relationName, org.neo4j.graphdb.Direction.INCOMING);
    }

    public Resource getResourceTo(String relationName) {
        // TODO enforce only one?
        return getRelatedResources(relationName, org.neo4j.graphdb.Direction.INCOMING).iterator()
            .next();
    }

    public Config getResponseTimeConfig() {
        return getConfig("ResponseTime");
    }

    public ResourceType getType() {
        return type;
    }

    public Integer getVersion() {
        return this.version;
    }

    public boolean isConfigUserManaged() {
        // TODO from ConfigResponseDB. remove?
        return true;
    }

    public boolean isOwner(Integer subjectId) {
        // TODO some overlord checking, then check owner's ID
        return true;
    }

    public boolean isRelatedTo(Resource resource, String relationName) {
        Traverser relationTraverser = getUnderlyingState().traverse(Traverser.Order.BREADTH_FIRST,
            new StopEvaluator() {

                public boolean isStopNode(TraversalPosition currentPos) {
                    return currentPos.depth() >= 1;
                }
            }, ReturnableEvaluator.ALL_BUT_START_NODE,
            DynamicRelationshipType.withName(relationName), org.neo4j.graphdb.Direction.OUTGOING);
        for (Node related : relationTraverser) {
            if (related.equals(resource.getUnderlyingState())) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public Resource merge() {
        if (this.entityManager == null)
            this.entityManager = entityManager();
        Resource merged = this.entityManager.merge(this);
        this.entityManager.flush();
        return merged;
    }

    @Transactional
    public void persist() {
        if (this.entityManager == null)
            this.entityManager = entityManager();
        this.entityManager.persist(this);
        // TODO this call appears to be necessary to get Alert populated with
        // its underlying node
        getId();
    }

    @Transactional
    public ResourceRelation relateTo(Resource resource, String relationName) {
        // TODO pre-populate System type (root resource type) can relate to
        // other Resource Types via Platform relation or Contains relation
        // if (type.getName().equals("System")) {
        // if (!(relationName.equals(RelationshipTypes.CONTAINS))) {
        // throw new InvalidRelationshipException();
        // }
        // }else
        if (!type.isRelatedTo(resource.getType(), relationName)) {
            throw new InvalidRelationshipException();
        }
        return (ResourceRelation) this.relateTo(resource, ResourceRelation.class, relationName);
    }

    @Transactional
    public void remove() {
        removeConfig();
        if (this.entityManager == null)
            this.entityManager = entityManager();
        if (this.entityManager.contains(this)) {
            this.entityManager.remove(this);
        } else {
            Resource attached = this.entityManager.find(this.getClass(), this.id);
            this.entityManager.remove(attached);
        }
    }

    private void removeConfig() {
        getMeasurementConfig().remove();
        getProductConfig().remove();
        getAutoInventoryConfig().remove();
        getControlConfig().remove();
        getResponseTimeConfig().remove();
    }

    public void removeProperties() {
        for (String key : getUnderlyingState().getPropertyKeys()) {
            getUnderlyingState().removeProperty(key);
        }
    }

    @Transactional
    public void removeRelationship(Resource resource, String relationName) {
        if (this.isRelatedTo(resource, relationName)) {
            this.removeRelationshipTo(resource, relationName);
        }
    }

    public void removeRelationships() {
        // TODO getRelationships only does one direction
        for (ResourceRelation relation : getRelationships()) {
            relation.getUnderlyingState().delete();
        }
    }

    public void removeRelationships(String relationName) {
        Iterable<Relationship> relationships = getUnderlyingState().getRelationships(
            DynamicRelationshipType.withName(relationName), org.neo4j.graphdb.Direction.BOTH);
        for (Relationship relationship : relationships) {
            relationship.delete();
        }
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    private void setConfig(Config config, String type) {
        Relationship rel = this.getUnderlyingState().createRelationshipTo(
            config.getUnderlyingState(), DynamicRelationshipType.withName("HAS_CONFIG"));
        rel.setProperty("configType", type);
    }

    public void setConfigUserManaged(boolean userManaged) {
        // TODO from ConfigResponseDB. remove?
    }

    public void setConfigValidationError(String error) {
        // TODO from ConfigResponseDB. remove?
    }
    
    public String getConfigValidationError() {
        // TODO from ConfigResponseDB. remove?
        return null;
    }

    public void setControlConfig(Config config) {
        setConfig(config, "Control");
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setMeasurementConfig(Config config) {
        setConfig(config, "Measurement");
    }

    public void setModifiedBy(String modifiedBy) {
        this.modifiedBy = modifiedBy;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setOwner(AuthzSubject owner) {
        this.owner = owner;
    }

    public void setProductConfig(Config config) {
        setConfig(config, "Product");
    }

    public Object setProperty(String key, Object value) {
        if (type.getPropertyType(key) == null) {
            throw new IllegalArgumentException("Property " + key +
                                               " is not defined for resource of type " +
                                               type.getName());
        }
        // TODO check other stuff?
        Object oldValue = getUnderlyingState().getProperty(key);
        getUnderlyingState().setProperty(key, value);
        return oldValue;
    }

    public void setResponseTimeConfig(Config config) {
        setConfig(config, "ResponseTime");
    }

    public void setType(ResourceType type) {
        this.type = type;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
    
    public boolean isInAsyncDeleteState() {
        //TODO remove
        return false;
    }

    public static int countResources() {
        return entityManager().createQuery("select count(o) from Resource o", Integer.class)
            .getSingleResult();
    }

    public static final EntityManager entityManager() {
        EntityManager em = new Resource().entityManager;
        if (em == null)
            throw new IllegalStateException(
                "Entity manager has not been injected (is the Spring Aspects JAR configured as an AJC/AJDT aspects library?)");
        return em;
    }

    public static List<Resource> findAllResources() {
        return entityManager().createQuery("select o from Resource o", Resource.class)
            .getResultList();
    }
 
    public static Collection<Resource> findByCTime(long ctime) {
        // TODO impl?
        return null;
    }

    public static Collection<Resource> findByOwner(AuthzSubject owner) {
        // TODO best way to implement cutting across to AuthzSubject
        return null;
    }

    public static Resource findResource(Integer id) {
        if (id == null)
            return null;
        return entityManager().find(Resource.class, id);
    }

    public static Resource findResourceByName(String name) {
        return new Resource().finderFactory.getFinderForClass(Resource.class).findByPropertyValue(
            "name", name);
    }

    public static List<Resource> findResourceEntries(int firstResult, int maxResults) {
        return entityManager().createQuery("select o from Resource o", Resource.class)
            .setFirstResult(firstResult).setMaxResults(maxResults).getResultList();
    }

    public static Resource findRootResource() {
        // TODO AuthzConstants.RootResourceId. We may need a root resource.
        // Check concept of Neo4J ref node
        return null;
    }
}
