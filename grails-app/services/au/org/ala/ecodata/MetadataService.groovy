package au.org.ala.ecodata

import au.org.ala.ecodata.metadata.OutputMetadata
import grails.converters.JSON
import org.grails.plugins.csv.CSVMapReader

import java.text.SimpleDateFormat
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

class MetadataService {

    private static final String ACTIVE = 'active'
    // The spatial portal returns n/a when the point does not intersect a layer.
    private static final String SPATIAL_PORTAL_NO_MATCH_VALUE = 'n/a'

    private static final int BATCH_LIMIT = 200

    def grailsApplication, webService, cacheService, messageSource

    def activitiesModel() {
        return cacheService.get('activities-model',{
            String filename = (grailsApplication.config.app.external.model.dir as String) + 'activities-model.json'
            JSON.parse(new File(filename).text)
        })
    }

    def activitiesList(programName) {

        def activities = activitiesModel().activities

        if (programName) {
            def program = programModel(programName)
            if (program.activities) {
                activities = activities.findAll{it.type in program.activities}
            }
        }

        // Remove deprecated activities
        activities = activities.findAll {!it.status || it.status == ACTIVE}

        Map byCategory = [:]

        // Group by the activity category field, falling back on a default grouping of activity or assessment.
        activities.each {
            def category = it.category?: it.type == 'Activity' ? 'Activities' : 'Assessment'
            if (!byCategory[category]) {
                byCategory[category] = []
            }
            def description = messageSource.getMessage("api.${it.name}.description", null, "", Locale.default)
            byCategory[category] << [name:it.name, description:description]
        }
        byCategory
    }

    def programsModel() {
        return cacheService.get('programs-model',{
            String filename = (grailsApplication.config.app.external.model.dir as String) + 'programs-model.json'
            JSON.parse(new File(filename).text)
        })
    }

    def programModel(program) {
        return programsModel().programs.find {it.name == program}
    }

    def getOutputModel(name) {
        return activitiesModel().outputs.find { it.name == name }
    }

    def getDataModelFromOutputName(outputName) {
        def activityName = getActivityModelName(outputName)
        return activityName ?: getOutputDataModel(activityName)
    }

    def getActivityModelName(outputName) {
        return activitiesModel().outputs.find({it.name == outputName})?.template
    }

    def getOutputDataModel(templateName) {
        return cacheService.get(templateName + '-model',{
            String filename = (grailsApplication.config.app.external.model.dir as String) + templateName + '/dataModel.json'
            JSON.parse(new File(filename).text)
        })
    }

    def getOutputDataModelByName(name) {
        def outputModel = activitiesModel().outputs.find{it.name == name}
        return getOutputDataModel(outputModel?.template)
    }

    def updateOutputDataModel(model, templateName) {
        log.debug "updating template name = ${templateName}"
        writeWithBackup(model, grailsApplication.config.app.external.model.dir, templateName, 'dataModel', 'json')
        // make sure it gets reloaded
        cacheService.clear(templateName + '-model')
    }

    def getModelName(output, type) {
        return output.template ?: getModelNameFromType(type)
    }

    def getModelNameFromType(type) {
        //log.debug "Getting model name for ${type}"
        return activitiesModel().find({it.name == type})?.template
    }

    def getInstitutionName(uid) {
        return uid ? institutionList().find({ it.uid == uid })?.name : ''
    }

    def institutionList() {
        return cacheService.get('institutions',{
            webService.getJson(grailsApplication.config.collectory.baseURL + 'ws/institution')
        })
    }

    /**
     * Returns the institution from the institutionList() that matches the supplied
     * name (using a case-insensitive match).
     * @param name the name of the institution to find.
     * @return the institution with the supplied name, or null if it cannot be found.
     */
    def findInstitutionByName(String name) {
        def lowerCaseName = name.toLowerCase()
        return institutionList().find{ it.name.toLowerCase() == lowerCaseName }
    }

    def writeWithBackup(content, modelPathRoot, path, filename, extension) {
        /* build path creating directories as required */
        // assume root ends with a path separator
        String filePath = modelPathRoot + path + (path.endsWith('/') ? '' : '/')
        // get new or existing file
        def f = new File(filePath + filename + '.' + extension)

        // create dirs as required
        new File(f.getParentFile().getAbsolutePath()).mkdirs()

        if (f.exists()) {
            // create a backup of the file appending the current timestamp to the name
            Date date = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            def backupFilename = filePath + filename + "-" + sdf.format(date) + '.' + extension
            f.renameTo(new File(backupFilename))
        }

        // write the new content
        f.withWriter { out ->
            out.write content as String
        }
    }

    def updateActivitiesModel(model) {
        writeWithBackup(model, grailsApplication.config.app.external.model.dir, '', 'activities-model', 'json')
        // make sure it gets reloaded
        cacheService.clear('activities-model')
    }

    def updateProgramsModel(model) {
        writeWithBackup(model, grailsApplication.config.app.external.model.dir, '', 'programs-model', 'json')
        // make sure it gets reloaded
        cacheService.clear('programs-model')
    }

    // Return the Nvis classes for the supplied location. This is an interim solution until the spatial portal can be fixed to handle
    // large grid files such as the NVIS grids.
    def getNvisClassesForPoint(Double lat, Double lon) {
        def retMap = [:]

        def nvisLayers = grailsApplication.config.app.facets.geographic.special

        nvisLayers.each { name, path ->
            def classesJsonFile = new File(path + '.json')
            if (classesJsonFile.exists()) { // The files are too big for the development system
                def classesJson = classesJsonFile.text
                def classesMap = JSON.parse(classesJson)

                BasicGridIntersector intersector = null
                try {
                    intersector = new BasicGridIntersector(path)
                    def classNumber = intersector.readCell(lon, lat)
                    retMap.put(name, classesMap[classNumber.toInteger().toString()])
                } catch (IllegalArgumentException ex) {
                    // Lat long was outside extent of grid
                    retMap.put(name, null)
                }
                finally {
                    if (intersector != null) {
                        intersector.close()
                    }
                }
            }
            else {
                retMap << [(name) : null]
            }

        }

        return retMap
    }

    /**
     * Attaches a label matching the form to each entry of the dataModel in the output model template for the
     * specified output.
     * @param outputName identifies the output to annotate.
     */
    def annotatedOutputDataModel(outputName) {
        def outputMetadata = getOutputDataModelByName(outputName)

        def annotatedModel = null
        if (outputMetadata) {
            OutputMetadata metadata = new OutputMetadata(outputMetadata)
            annotatedModel = metadata.annotateDataModel()
        }
        annotatedModel
    }


    /**
     * This method produces the location metadata for a point, used in particular to provide the geographic facet terms
     * for a site.  This is done by intersecting the site centroid against a configured set of spatial portal layers
     * and storing the results against attributes that are indexed specifically for faceting.
     * @param lat the latitude of the point.
     * @param lng the longitude of the point.
     * @return metadata for the point.
     */
    def getLocationMetadataForPoint(lat, lng) {

        def features = performLayerIntersect(lat, lng)

        def localityUrl = grailsApplication.config.google.geocode.url + "${lat},${lng}"
        def result = webService.getJson(localityUrl)
        def localityValue = (result?.results && result.results)?result.results[0].formatted_address:''
        features << [locality: localityValue]

        // Return the Nvis classes for the supplied location. This is an interim solution until the spatial portal can be fixed to handle
        // large grid files such as the NVIS grids.
        features << getNvisClassesForPoint(lat as Double, lng as Double)

        features
    }

    /**
     * Reads the facet configuration and intersects the supplied point against the defined facets.
     * @param lat the latitude of the point.
     * @param lng the longitude of the point.
     * @return metadata for the point obtained from the spatial portal.
     */
    def performLayerIntersect(lat,lng) {


        def contextualLayers = grailsApplication.config.app.facets.geographic.contextual
        def groupedFacets = grailsApplication.config.app.facets.geographic.grouped

        // Extract all of the layer field ids from the facet configuration so we can make a single web service call to the spatial portal.
        def fieldIds = contextualLayers.collect { k, v -> v }
        groupedFacets.each { k, v ->
            fieldIds.addAll(v.collect { k1, v1 -> v1 })
        }

        // Do the intersect
        def featuresUrl = grailsApplication.config.spatial.intersectUrl + "${fieldIds.join(',')}/${lat}/${lng}"
        def features = webService.getJson(featuresUrl)

        def facetTerms = [:]

        if (features instanceof List) {
            contextualLayers.each { name, fid ->
                def match = features.find { it.field == fid }
                if (match && match.value && !SPATIAL_PORTAL_NO_MATCH_VALUE.equalsIgnoreCase(match.value)) {
                    facetTerms << [(name): match.value]
                }
                else {
                    facetTerms << [(name): null]
                }
            }

            groupedFacets.each { group, layers ->
                def groupTerms = []
                layers.each { name, fid ->
                    def match = features.find { it.field == fid }
                    if (match && match.value && !SPATIAL_PORTAL_NO_MATCH_VALUE.equalsIgnoreCase(match.value)) {
                        groupTerms << match.layername
                    }
                }
                facetTerms << [(group): groupTerms]
            }
        }
        else {
            log.warn("Error performing intersect for lat=${lat} lng=${lng}, result=${features}")
        }

        facetTerms
    }

     private def buildPointsArray(sites) {

        def points = ""
        def pointsArray = []

        sites?.eachWithIndex { site, i ->
            if(points){
                points = "${points},${site.extent.geometry.centre[1]},${site.extent.geometry.centre[0]}"
            }
            else{
                points = "${site.extent.geometry.centre[1]},${site.extent.geometry.centre[0]}"
            }
            if(((i+1) % BATCH_LIMIT) == 0){
                pointsArray.add(points)
                points = ""
            }
        }
        if(points){
            pointsArray.add(points)
        }

        pointsArray
    }

    private def buildFieldIds(sites){
        def contextualLayers = grailsApplication.config.app.facets.geographic.contextual
        def groupedFacets = grailsApplication.config.app.facets.geographic.grouped
        def fieldIds = contextualLayers.collect { k, v -> v }
        groupedFacets.each { k, v ->
            fieldIds.addAll(v.collect { k1, v1 -> v1 })
        }
        fieldIds
    }

    /**
     * Download spatial layers.
     * Spatial service api: http://spatial.ala.org.au/ws/
     * Example format: http://spatial-dev.ala.org.au/ws/intersect/batch?fids=cl958,cl927&points=-29.911,132.769,-20.911,122.769
     * @param list of available sites.
     * @return raw spatial data
     */
    private def downloadSpatialLayers(sites){

        def pointsArray = buildPointsArray(sites)

        def fieldIds = buildFieldIds(sites)

        def results = []

        for(int i = 0; i < pointsArray?.size(); i++) {
            log.info("${(i+1)}/${pointsArray.size()} batch process started..")

            def featuresUrl = grailsApplication.config.spatial.intersectBatchUrl + "?fids=${fieldIds.join(',')}&points=${pointsArray[i]}"
            def status = webService.getJsonRepeat(featuresUrl)
            if(status?.error){
                throw new Exception("Webservice error, failed to get JSON after 12 tries.. - ${status}")
            }

            def download, timeout = 0
            while ( !(download = webService.getJson(status?.statusUrl))?.status?.equals("finished") && timeout < 12){ // break out after 1 min
                sleep(5000)
                timeout++
                log.info("${(i+1)}/${pointsArray.size()} - In the waiting queue, trying again..")
            }
            if(download?.error || timeout >= 12){
                log.info("${(i+1)}/${pointsArray.size()} - failed after 12 tries..")
                throw new Exception("Webservice error, failed to get JSON - ${download}")
            }

            URL downloadURL = new URL(download?.downloadUrl)
            ZipInputStream zipIn = new ZipInputStream(downloadURL?.openStream());
            ZipEntry entry;

            StringBuilder s = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read = 0;
            while ((entry = zipIn?.getNextEntry()) !=  null) {
                while ((read = zipIn.read(buffer, 0, 1024)) >= 0) {
                    s.append(new String(buffer, 0, read));
                }
                results += new CSVMapReader(new StringReader(s.toString())).readAll()
            }

            log.info("${(i+1)}/${pointsArray.size()} batch process completed..")
        }

        results
    }

    private def getValidSites(allSites){

        def sites = []

        log.info("Total sites = ${allSites?.size()}")

        allSites.each{ site ->
            if (!site.projects) {
                log.info("Ignoring site ${site.siteId} due to no associated projects")
                return
            }
            def centroid = site.extent?.geometry?.centre
            if (centroid && centroid.size() == 2) {
                sites.add(site)
            }
            else {
                log.error("Unable to update metadata for site: ${site.siteId}, no centroid exists.")
            }
        }

        log.info("Total sites with valid points = ${sites.size()}")

        sites
    }

    private def getGridAndFacetLayers(layers,lat,lng){

        def siteResult = layers.find{it['latitude'] == (lat as String) && it['longitude'] == (lng as String)} ?: [:]
        if (!siteResult) {
            log.error("Missing result for ${lat}, ${lng}")
        }

        def contextualLayers = grailsApplication.config.app.facets.geographic.contextual
        def groupedFacets = grailsApplication.config.app.facets.geographic.grouped
        def facetTerms = [:]

        contextualLayers.each { name, fid ->
            def match = siteResult[fid]
            if (match && !SPATIAL_PORTAL_NO_MATCH_VALUE.equalsIgnoreCase(match)) {
                facetTerms << [(name): match]
            }
            else {
                facetTerms << [(name): null]
            }
        }

        groupedFacets.each { group, entry ->
            def groupTerms = []
            entry.each { name, fid ->
                def match = siteResult[fid]
                if (match && !SPATIAL_PORTAL_NO_MATCH_VALUE.equalsIgnoreCase(match)) {
                    groupTerms << match
                }
            }
            facetTerms << [(group): groupTerms]
        }

        facetTerms
    }

    /**
     * Updates sites extent properties from the values obtained from
     * 1. Spatial server for layer information.
     * 2. Google server for location and
     * 3. NVIS classes info.
     * These data's are used for geographic facet terms for a site.
     *
     * @param list of available sites.
     * @return sites with the updated extent values.
     */
    def getLocationMetadataForSites(allSites, boolean includeLocality = true) {

        def sites = getValidSites(allSites)

        def layers = downloadSpatialLayers(sites)

        log.info("Initiating extent mapping")

        sites.eachWithIndex { site, index ->

            def lat = site.extent.geometry.centre[1]
            def lng = site.extent.geometry.centre[0]

            def features = [:]
            if (includeLocality) {
                def localityUrl = grailsApplication.config.google.geocode.url + "${lat},${lng}"
                def result = webService.getJson(localityUrl)
                def localityValue = (result?.results && result.results) ? result.results[0].formatted_address : ''
                features << [locality: localityValue]
            }
            features << getNvisClassesForPoint(lat as Double, lng as Double)
            features << getGridAndFacetLayers(layers,lat,lng)

            site.extent.geometry.putAll(features)
            if(index > 0 && (index % BATCH_LIMIT) == 0){
                log.info("Completed (${index+1}) extent mapping")
            }
        }

        log.info("Completed batch processing and site extent mapping..")

        sites
    }
}
