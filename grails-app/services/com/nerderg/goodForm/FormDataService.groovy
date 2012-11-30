package com.nerderg.goodForm

import com.nerderg.goodForm.form.Form
import com.nerderg.goodForm.form.FormElement
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import java.text.ParseException
import com.nerderg.goodForm.form.Question
import net.sf.json.JSONObject
import org.codehaus.groovy.grails.plugins.web.taglib.ValidationTagLib

/**
 *
 * Copied from GrantFormService
 */
class FormDataService {

    def goodFormService

    def rulesEngineService

    def g = new ValidationTagLib()

    Map<Long, Form> forms = [:]

    Map<String, List<Closure>> validators = createDefaultValidators()

    /**
     * Adds the default validators to the validator list.
     * @return
     */
    Map<String, List<Closure>> createDefaultValidators() {
        validators = [:]
        addValidator("*", {formElement, fieldValue -> validateMandatoryField(formElement, fieldValue)})
        addValidator("*", {formElement, fieldValue -> validateDate(formElement, fieldValue)})
        addValidator("*", {formElement, fieldValue -> validatePattern(formElement, fieldValue)})
        return validators
    }

    Form getForm(String formName) {
        return getFormQuestions(formDefinitionForName(formName))
    }

    /**
     *
     * @return the FormDefinition with a name equal to <code>formName</code> that has the max formVersion value
     */
    FormDefinition formDefinitionForName(String formName) {
        FormDefinition.executeQuery(
                """select f from FormDefinition f
                   where name = ?
                   and f.formVersion =
                         (select max(ff.formVersion)
                         from FormDefinition ff
                         where ff.id = f.id)
                         """, [formName]).first()

    }

    Form getFormQuestions(FormDefinition formDefinition) {
        String key = formDefinition.name + formDefinition.formVersion
        if (!forms[key]) {
            Form form = createForm(formDefinition)
            forms[key] = form
        }
        return forms[key]
    }

    Form createForm(FormDefinition formDefinition) {
        Form form = GoodFormService.compileForm(formDefinition.formDefinition)
        form.version = formDefinition.version
        form.name = formDefinition.name
        form.formDefinitionId = formDefinition.id
        return form

    }

    FormInstance checkInstance(Long id) {
        FormInstance instance = FormInstance.get(id)
        //TODO include security check?
        return instance
    }

    /**
     * @param state
     * @return
     */
    Map cleanUpStateParams(Map state) {
        // params are broken into maps for each question
        // first remove all thing.thing.thing values because we only want to base values or the maps
        Map intermediate = state.findAll {
            it.key.indexOf('.') < 0
        }
        // now do the same for all the map values (recursively)
        intermediate.each {
            if (it.value instanceof Map) {
                it.value = cleanUpStateParams(it.value)
            }
        }
        return intermediate
    }

    /**
     * Process the returned field values by first validating the field then getting references, saving attachments
     * and finally converting to typed fields from string (mainly numeric fields)
     *
     * Numeric fields number and money are converted to BigDecimal
     *
     * @param formElement
     * @param formData the current data Map
     * @param instance the FormInstance object
     * @return true on error
     */
    boolean validateAndProcessFields(FormElement formElement, Map formData, FormInstance instance) {
        //note makeElement name uses the attr.name of it's parent so it must be set. (side effect)
        boolean error = false
        if (formElement.attr.heading) {
            return error // ignore headings
        }
        formElement.attr.name = goodFormService.makeElementName(formElement)
        formElement.attr.error = ""
        def fieldValue = goodFormService.findField(formData, formElement.attr.name)

        if (fieldValue instanceof String[]) {
            fieldValue.each {
                error = validateField(formElement, it, error)
            }
        } else {
            error = validateField(formElement, fieldValue, error)
        }

        //get attached file and store it, save the reference to it in the formData
        if (formElement.attr.containsKey('attachment')) {
            //get the uploaded file and store somewhere
            def f = request.getFile(formElement.attr.name)
            if (f && !f.empty) {
                String basedir = ConfigurationHolder.config.uploaded.file.location.toString() + 'applications/' + instance.id
                File location = new File(basedir)
                location.mkdirs()
                def fieldSplit = formElement.attr.name.split(/\./)
                String filename = "${fieldSplit[0]}.${fieldSplit.last()}-${f.getOriginalFilename()}"
                File upload = new File(location, filename)
                f.transferTo(upload)
                goodFormService.setField(formData, formElement.attr.name, upload.name)
            } else {
                //todo refactor so we don't continually get the stored FormData also dangerous for overwrite
                def existingFile = goodFormService.findField(instance.storedFormData(), formElement.attr.name)
                if (existingFile) {
                    goodFormService.setField(formData, formElement.attr.name, existingFile)
                } else {
                    goodFormService.setField(formData, formElement.attr.name, 'none')
                }
            }
        }

        //handle subElements
        if (formElement.attr.containsKey('each')) {
            //handle each which dynamically adds elements
            goodFormService.processEachFormElement(formElement, formData) {Map subMap ->
                error = validateAndProcessFields(subMap.element, formData, instance) || error
            }
        } else {
            formElement.subElements.each { FormElement sub ->
                error = validateAndProcessFields(sub, formData, instance) || error
            }
        }

        //convert numeric fields to bigdecimal
        try {
            if (fieldValue && (formElement.attr.containsKey('number') || formElement.attr.containsKey('money'))) {
                log.debug "converting ${formElement.attr.name} value ${fieldValue} to bigdecimal"
                if (fieldValue instanceof String[] || fieldValue instanceof List) {
                    goodFormService.setField(formData, formElement.attr.name, fieldValue.collect {
                        if (it) {
                            it as BigDecimal
                        }
                    })
                } else {
                    goodFormService.setField(formData, formElement.attr.name, fieldValue as BigDecimal)
                }
            }
        } catch (NumberFormatException e) {
            log.error "${e.message} converting $fieldValue to number"
            //TODO i18n
            formElement.attr.error += "$fieldValue isn't a number."
            error = true
        }

        return error
    }

    /**
     * Retrieves and invokes the validators that have been added for the formElement.
     *
     * @param formElement
     * @param fieldValue
     * @param error
     * @return true if the field contains errors, false if not
     */
    boolean validateField(FormElement formElement, fieldValue, boolean error) {

        //iterate over validators
        List<Closure> validators = getValidatorsForElement(formElement)
        validators.each {
            //invoke closure
            error = error || it(formElement, fieldValue)
        }
        return error
    }

    List<Closure> getValidatorsForElement(FormElement formElement) {
        return validators.findAll { it.key == formElement.attr || it.key == "*"}.values() as List<Closure>
    }

    /**
     * Performs validation of date form elements.
     *
     * @param fieldValue
     * @param formElement
     * @return
     */
    private boolean validateDate(fieldValue, FormElement formElement) {
        def error = false
        if (fieldValue && formElement.attr.containsKey('date')) {
            try {
                Date d = Date.parse(formElement.attr.date, fieldValue)
                if (formElement.attr.max) {
                    if (formElement.attr.max == 'today') {
                        if (d.time > System.currentTimeMillis()) {
                            formElement.attr.error += g.message(code: "goodform.validate.date.future")
                            error = true
                        }
                    } else {
                        Date max = Date.parse(formElement.attr.date, formElement.attr.max)
                        if (d.time > max.time) {
                            formElement.attr.error += g.message(code: "goodform.validate.date.greaterThan", args: [formElement.attr.max])
                            error = true
                        }
                    }
                }
                if (formElement.attr.min) {
                    Date min = Date.parse(formElement.attr.date, formElement.attr.min)
                    if (d.time < min.time) {
                        formElement.attr.error += g.message(code: "goodform.validate.date.lessThan", args: [formElement.attr.min])
                        error = true
                    }
                }
            } catch (ParseException e) {
                formElement.attr.error += g.message(code: "goodform.validate.date.invalid")
                error = true

            }
        }
        return error
    }

    /**
     * Validates that a field value matches a defined regex pattern.
     *
     * @param fieldValue
     * @param formElement
     * @return
     */
    private boolean validatePattern(fieldValue, FormElement formElement) {
        def error = false
        if (fieldValue && formElement.attr.containsKey('pattern')) {
            String pattern
            String message = g.message(code: "goodform.validate.invalid.pattern")
            if (formElement.attr.pattern instanceof List) {
                pattern = formElement.attr.pattern[0]
                if (formElement.attr.pattern.size() > 1) {
                    message = formElement.attr.pattern[1]
                }
            } else {
                pattern = formElement.attr.pattern
            }
            if (fieldValue && !(fieldValue ==~ pattern)) {
                formElement.attr.error += message
                error = true
            }
        }
        return error
    }

    /**
     * Validates that a required field is present.
     *
     * @param formElement
     * @param fieldValue
     * @return
     */
    private boolean validateMandatoryField(FormElement formElement, fieldValue) {
        def error = false
        if (formElement.attr.containsKey('required') && (fieldValue == null || fieldValue == '')) {
            formElement.attr.error += g.message(code: "goodform.validate.required.field")
            error = true
        }
        return error
    }

    /**
     *
     * Get the questions that have been answered so far up to the current question set
     * @param instance
     * @param questions
     * @return answered questions
     */
    def getAnsweredQuestions(FormInstance instance, Form form) {

        List answered = []
        List state = instance.storedState()
        List currentQuestions = instance.storedCurrentQuestion()

        def i = 0
        List qSet
        while (i < state.size() && (qSet = state[i++] as List) != currentQuestions) {
            goodFormService.withQuestions(qSet, form) { q, qRef ->
                answered.add(q)
            }
        }
        return answered
    }

    FormInstance createFormInstance(Form form, Map formData) {
        FormInstance instance = new FormInstance(started: new Date(), userId: 'unknown', givenNames: 'unknown', lastName: 'unknown', currentQuestion: formData.next.last(), formDefinitionId: form.formDefinitionId)
        instance.storeFormData(formData)
        instance.storeState([formData.next])
        instance.storeCurrentQuestion(formData.next)
        instance.formVersion = form.version
        instance.formDefinitionId = form.formDefinitionId
        instance.save()
        return instance
    }

    /**
     *
     * @param refs
     * @param form
     * @return
     */
    List<Question> getSubset(Collection refs, Form form) {
        List<Question> questions = []
        refs.each {
            Question q = form[it]
            if (q) {
                questions.add(q)
            } else {
                log.error "Question $it not found."
                throw new FormDataException("Question $it not found.")
            }
        }
        return questions
    }

    /**
     *
     * Process the form data through the rules engine. The rules engine returns the next set of questions to be asked.
     * We check through the existing form data to see if all the next set of questions have been answered and if so we ask
     * the rules engine to check the next set until we find a question in a set that hasn't been answered already.
     *
     * This way we skip forward through the questions that have been answered to only ask relevant questions
     *
     * One side effect is that we set the flash.message on the way through,perhaps we shouldn't
     * @param instance
     * @param mergedFormData
     * @return processedFormData up to the next un-asked question
     */
    def Map processNext(FormInstance instance, Map mergedFormData) {
        String lastQuestion = instance.storedCurrentQuestion().last()
        FormDefinition definition = FormDefinition.findById(instance.formVersion)
        String ruleName = definition.name + lastQuestion
        mergedFormData.remove('next')  //prevent possible pass through by rules engine
        try {
            JSONObject processedJSONFormData = rulesEngineService.ask(ruleName, mergedFormData)
            def processedFormData = rulesEngineService.cleanUpJSONNullMap(processedJSONFormData)

            if (processedFormData[lastQuestion].message) {
                //TODO how to handle adding message into flash?
                //flash.message = processedFormData[lastQuestion].message
            }

            updateStoredFormInstance(instance, processedFormData)

            if (processedFormData.next.size() == 1 && processedFormData.next[0] == 'End') {
                return processedFormData
            }
            //prevent loops if rules engine sends you back to the same questions
            if (processedFormData.next.contains(lastQuestion)) {
                return processedFormData
            }

            //search for answers to the next questions - if we don't have an answer we ask this question set
            for (String q in processedFormData.next) {
                if (!processedFormData[q] || processedFormData[q].recheck) {
                    return processedFormData
                }
            }
            //otherwise we check the next lot
            return processNext(instance, processedFormData)
        } catch (RulesEngineException e) {
            log.error "Calling rule $ruleName, instance $instance.id: $e"
            throw e
        }
    }

    def updateStoredFormInstance(FormInstance instance, Map processedFormData) {

        List state = instance.storedState()
        def nextInState = state.find { s ->
            s == processedFormData.next
        }
        if (!nextInState) {
            state.add(processedFormData.next)
            instance.storeState(state)
        }
        instance.storeCurrentQuestion(processedFormData.next)
        instance.storeFormData(processedFormData)
    }

    /**
     * Remove all the state after the currentQuestion list of questions.
     * If the current question doesn't exist don't truncate anything
     * @param state
     * @param currentQuestion
     * @return A new truncated list
     */
    def List truncateState(List<List> state, List currentQuestion) {
        List trunkState = []
        def i = 0
        List s = state[i]
        boolean found = false
        while (i < state.size() && !found) {
            trunkState.add(s)
            found = (s == currentQuestion)
            s = state[++i]
        }
        return trunkState
    }

    /**
     * Adds the closure to the validator map.
     *
     * @param fieldName
     * @param cli
     * @return
     */
    def addValidator(String fieldName, Closure cli) {
        if (!validators.get(fieldName)) {
            validators.put(fieldName, new ArrayList<Closure>())
        }
        validators.get(fieldName).add(cli)

    }

}
class FormDataException extends Exception {
    def FormDataException() {
        super()
    }

    def FormDataException(String message) {
        super(message)
    }

    def FormDataException(String message, Throwable cause) {
        super(message, cause)
    }

    def FormDataException(Throwable cause) {
        super(cause)
    }

}
