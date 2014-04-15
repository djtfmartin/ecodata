package au.org.ala.ecodata

import au.org.ala.ecodata.reporting.Score

class ProjectService {

    static transactional = false
    static final ACTIVE = "active"
    static final BRIEF = 'brief'
    static final FLAT = 'flat'
    static final ALL = 'all'

    def grailsApplication
    def siteService
    def documentService
    def metadataService
    def reportService
    def activityService

    def getCommonService() {
        grailsApplication.mainContext.commonService
    }

    def getBrief(listOfIds) {
        Project.findAllByProjectIdInListAndStatus(listOfIds, ACTIVE).collect {
            [projectId: it.projectId, name: it.name]
        }
    }

    def get(String id, levelOfDetail = []) {
        def p = Project.findByProjectId(id)

        return p?toMap(p, levelOfDetail):null
    }

    def list(levelOfDetail = [], includeDeleted = false) {
        def list = includeDeleted ? Project.list() : Project.findAllByStatus(ACTIVE)
        list.collect { toMap(it, levelOfDetail) }
    }

    /**
     * Converts the domain object into a map of properties, including
     * dynamic properties.
     * @param prj a Project instance
     * @return map of properties
     */
    def toMap(prj, levelOfDetail = []) {
        def dbo = prj.getProperty("dbo")
        def mapOfProperties = dbo.toMap()
        if (levelOfDetail == BRIEF) {
            return [projectId: prj.projectId, name: prj.name]
        }
        def id = mapOfProperties["_id"].toString()
        mapOfProperties["id"] = id
        mapOfProperties.remove("_id")
        if (levelOfDetail != FLAT) {
            mapOfProperties.remove("sites")
            mapOfProperties.sites = siteService.findAllForProjectId(prj.projectId, levelOfDetail)
            mapOfProperties.documents = documentService.findAllForProjectId(prj.projectId, levelOfDetail)
            if (levelOfDetail == ALL) {
                mapOfProperties.activites = []
                getActivityIdsForProject(prj.projectId).each {
                    mapOfProperties.activites << activityService.get(it, ActivityService.ACTIVE)
                }
                // Don't want to duplicate the activities as they can be large, so remove from the sites.
                mapOfProperties.sites?.each { it.remove('activities')}
            }
        }
        mapOfProperties.findAll {k,v -> v != null}
    }

    /**
     * Converts the domain object into a highly detailed map of properties, including
     * dynamic properties, and linked components.
     * @param prj a Project instance
     * @return map of properties
     */
    def toRichMap(prj) {
        def dbo = prj.getProperty("dbo")
        def mapOfProperties = dbo.toMap()
        def id = mapOfProperties["_id"].toString()
        mapOfProperties["id"] = id
        mapOfProperties.remove("_id")
        mapOfProperties.remove("sites")
        mapOfProperties.sites = siteService.findAllForProjectId(prj.projectId, true)
        // remove nulls
        mapOfProperties.findAll {k,v -> v != null}
    }

    def loadAll(list) {
        list.each {
            create(it)
        }
    }

    def create(props) {
        assert getCommonService()
        def o = new Project(projectId: Identifiers.getNew(true,''))
        o.name = props.name // name is a mandatory property and hence needs to be set before dynamic properties are used (as they trigger validations)

        try {
            props.remove('sites')
            props.remove('id')
            getCommonService().updateProperties(o, props)
            return [status:'ok',projectId:o.projectId]
        } catch (Exception e) {
            // clear session to avoid exception when GORM tries to autoflush the changes
            Project.withSession { session -> session.clear() }
            def error = "Error creating project - ${e.message}"
            log.error error
            return [status:'error',error:error]
        }
    }

    def update(props, id) {
        def a = Project.findByProjectId(id)
        if (a) {
            try {
                getCommonService().updateProperties(a, props)
                return [status:'ok']
            } catch (Exception e) {
                Project.withSession { session -> session.clear() }
                def error = "Error updating project ${id} - ${e.message}"
                log.error error
                return [status:'error',error:error]
            }
        } else {
            def error = "Error updating project - no such id ${id}"
            log.error error
            return [status:'error',error:error]
        }
    }

    /**
     * Returns the reportable metrics for a project as determined by the project output targets and activities
     * that have been undertaken.
     * @param id identifies the project.
     * @return a Map containing the aggregated results.  TODO document me better, but it is likely this structure will change.
     *
     */
    def projectMetrics(String id) {
        def p = Project.findByProjectId(id)
        if (p) {
            def project = toMap(p, ProjectService.FLAT)

            def toAggregate = []

            metadataService.activitiesModel().outputs?.each{
                Score.outputScores(it).each { score ->
                    toAggregate << [score:score]
                }
            }
            toAggregate << [score:new Score([outputName:'Revegetation Details', aggregationType:Score.AGGREGATION_TYPE.SUM, name:'totalNumberPlanted', label:'Number of plants planted', units:'kg'] ), groupBy:[groupTitle: 'Plants By Theme', entity:'activity', property:'mainTheme']]
            toAggregate << [score:new Score([outputName:'Weed Treatment Details', aggregationType:Score.AGGREGATION_TYPE.SUM, name:'areaTreatedHa', listName:'weedsTreated', label:'Area treated', units:'ha'] ), groupBy:[groupTitle: 'Area treated by species', entity:'output',  property:'targetSpecies.name']]

            def outputSummary = reportService.projectSummary(id, toAggregate)


            // Add project output target information where it exists.

            project.outputTargets?.each { target ->

                def score = outputSummary.find{it.score.isOutputTarget && it.score.outputName == target.outputLabel && it.score.label == target.scoreLabel}
                if (score) {
                    score['target'] = target.target
                } else {
               		   // If there are no Outputs recorded containing the score, the results won't be returned, so add
               			// one in containing the target.
                    score = toAggregate.find{it.score?.outputName == target.outputLabel && it.score?.label == target.scoreLabel}
                    if (score) {
                        outputSummary << [score:score.score, target:target.target]
                    } else {
                        // This can happen if the meta-model is changed after targets have already been defined for a project.
                        // Once the project output targets are re-edited and saved, the old targets will be deleted.
                        log.warn "Can't find a score for existing output target: $target.outputLabel $target.scoreLabel, projectId: $project.projectId"
                    }
                }
            }
            return outputSummary
        } else {
            def error = "Error retrieving metrics for project - no such id ${id}"
            log.error error
            return [status:'error',error:error]
        }
    }

    public List<String> getActivityIdsForProject(String projectId) {
        def c = Activity.createCriteria()
        def list = c {
            eq("projectId", projectId)
            projections {
                property("activityId")
            }
        }
        List<String> results = new ArrayList<String>()
        list.each {
            results << it.toString()
        }
        return results
    }

}
