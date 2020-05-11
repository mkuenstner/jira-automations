import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.bc.issue.IssueService.TransitionValidationResult
import com.atlassian.jira.util.ErrorCollection
import com.atlassian.jira.issue.link.IssueLinkManager
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.CustomField
import com.onresolve.scriptrunner.runner.customisers.ContextBaseScript
import groovy.transform.BaseScript
 
@BaseScript ContextBaseScript context
 
/**
 * SecurityOfficeAutomations - Jira Post Function script called by ScriptRunner Add-On
 *
 * This script contains a series of functions to be executed for various Workflow 
 * circumstances concerning Epics and the linked tickets associated to it.
 
 * There are five main use cases:
 * 
 * 1) If ANY Story or Task is transitioned to IN PROGRESS, ensure parent Epic is also transitioned to IN PROGRESS if not already in that state.
 *
 * 2) If ALL Stories and Tasks are in the state TO DO, then ensure parent Epic is also transitioned to TO DO if not already in that state.
 *
 * 3) If ALL Stories and Tasks are in the state DONE, then ensure parent Epic is also transitioned to DONE if not already in that state.
 *
 * 4) If parent Epic is in the state TO DO, then ensure ALL linked Stories and Tasks are also transitioned to TO DO if not already in that state.
 *
 * 5) If parent Epic is in the state DONE, then ensure ALL linked Stories and Tasks are also transitioned to DONE if not already in that state.
 *
 *
 * Groovy for Scriptrunner
 * James Herbert
 * Tamedia AG
 * 2019
 */
 
 
 /** CONFIG */
 
 /** @authContext - used for authentication in jira */
 def authContext = ComponentAccessor.getJiraAuthenticationContext()
 
 /** @autoUser - Sets the application user with which all jira automations will be performed, unless otherwise overridden */
 def ApplicationUser autoUser = ComponentAccessor.getUserManager().getUserByKey("jira-auto-user")
 
 /** @issueService - used to for CRUDing issues within Jira */
 def IssueService issueService = ComponentAccessor.getIssueService()
 
 /** @issueLinkManager - used to access linked Issues */ 
 def IssueLinkManager issueLinkManager = ComponentAccessor.getIssueLinkManager()
 
 /** @customFieldManager - used for CRUDing Custom Fields in jira */
 def CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
 
 /** @currentUser - container for any manual usage within the workflow for tracking purposes */
 def ApplicationUser currentUser = ComponentAccessor.jiraAuthenticationContext.getLoggedInUser()
 
 /** @epicLink - pointer to the Epic Link custom field on tickets in jira*/
 def epicLink = customFieldManager.getCustomFieldObjectByName('Epic Link');
 
 /** Issue Meta Data */
 
 def ISSUE_TYPE = issue.issueType.name
 def ISSUE_STATE = issue.getStatus().name
 
 /** Kinda-Enums */
 
 def ISSUE_TYPE_EPIC = "Epic"
 def ISSUE_TYPE_STORY = "Story"
 def ISSUE_TYPE_TASK = "Task"
 def ISSUE_TYPE_SUBTASK = "Sub-task"
 
 // SECOF Risk Tower Statuses
 
 def STATE_TODO = "To Do";
 def STATE_IN_PROGRESS = "In Progress";
 def STATE_DONE = "Done"
 
 // SECOF Risk Tower Workflow transition numbers:
 
 def SECOF_RT_SpecifyWork = 11      // transitions to TO DO
 def SECOF_RT_StartWork = 21        // transitions to IN PROGRESS
 def SECOF_RT_FinishWork = 31       // transitions to DONE
 def SECOF_RT_Reopen = 41           // transitions to OPEN
 
 // SECOF Risk Tower Simple Task Workflow transition numbers:
 
 def SECOF_RT_ST_SpecifyWork = 71   // transitions to TO DO
 def SECOF_RT_ST_StartWork = 11     // transitions to IN PROGRESS
 def SECOF_RT_ST_FinishWork = 21    // transitions to DONE
 def SECOF_RT_ST_NeedMoreInfo = 31  // transitions to OPEN
 def SECOF_RT_ST_CantWork = 41      // transitions to ON HOLD
 def SECOF_RT_ST_RestartWork = 51   // transitions to IN PROGRESS
 def SECOF_RT_ST_Reopen = 61        // transitions to OPEN
  
 /** Function Library */
 
 def addCommentToTicket(Issue issue, ApplicationUser user, String message) {
    ComponentAccessor.getCommentManager().create(issue, user, message, true)
 }
 
 def addErrorsAsCommentToTicket(Issue issue, ApplicationUser user, String message, ErrorCollection errors) {
     //true at the end fires an IssueCommented event to any listeners
    ComponentAccessor.getCommentManager().create(issue, user, message + "--> due to the following Errors: " + errors.toString(), true)
     
 }
 
 // func: transitionFailure to process any errors into something readable by humans
 def transitionFailure(Issue issue, int desiredTransition, ErrorCollection errorCollection, ApplicationUser autoUser, transientVars, boolean addCommentToTicket) {
     log.error("Failed to apply transition number " + desiredTransition + " to Issue: " + issue.getKey() + " : " + errorCollection.toString());
     //add a comment to the ticket itself
     if(addCommentToTicket) {
        addErrorsAsCommentToTicket(issue, autoUser, "Failed to automatically transition (" + desiredTransition + ") this ticket ( " + issue.getKey() + ")", errorCollection);
        }
 }
 
 //func: transition an Issue to a Desired State via Jira Workflow transition number
 def transitionIssueToDesiredState = {Issue issue, int desiredTransition, boolean addCommentOnSuccess, boolean addCommentOnFailure ->
     def issueInputParameters = issueService.newIssueInputParameters()
     TransitionValidationResult result = issueService.validateTransition(autoUser, issue.id, desiredTransition, issueInputParameters)
 
     if (result.isValid()) {
         log.warn("transitionIssueToDesiredState: Transition " + desiredTransition + " is valid  - continuing...")
         def actualResult = issueService.validateTransition(autoUser, issue.id, desiredTransition, issueInputParameters)
         issueService.transition(autoUser, actualResult)   
         if(addCommentOnSuccess) {
             addCommentToTicket(issue, autoUser, "[SECOF Automation] - this ticket has been successfully auto-transitioned via transition ID "+desiredTransition+".  If this is incorrect, please contact your Jira Admin.")             
         }
     } else {
         log.error("transitionIssueToDesiredState: CreationValidationResult failed - transition " + desiredTransition + " is not valid at this point in the Workflow for Issue " + issue.getKey());
         def errorCollection = result.errorCollection;
         transitionFailure(issue, desiredTransition, errorCollection, autoUser, transientVars, addCommentOnFailure)
     }
 } 
 
 // func: check if an Issue IS NOT in a desired Status
 def isIssueNotInState = {Issue issue, String stateToCheck ->
     if (issue.getStatus().name != stateToCheck) {
         return true
     } else { 
         return false
     }
 }
 
 // func: check if an Issue IS in a desired Status
 def isIssueInState = {Issue issue, String stateToCheck ->
     if (issue.getStatus().name == stateToCheck) {
         return true
     } else { 
         return false
     }
 }
 
 //func: get the parent Epic via any Story, Task or Subtasks epic link
 def getEpicFromSubticketEpicLink = { Issue issue ->
     Issue epic = issue.getCustomFieldValue(epicLink) as Issue
     return epic
 }
 
 // func: check ALL of Epic's linked Tickets for a desired Status. If everyone is a match then, true will be returned.
 def checkAllLinkedTickets_STORY_TASK_SUBTASK_ONLY_OnEpicForDesiredState = {Issue epic, String stateToCheck -> 
     //assume all is well!
     log.warn("STEP - Checking all Linked Tickets for Epic ("+epic.getKey() + ") - ALL Child Ticket States must be aligned to " + stateToCheck + " in order to transition it.")
     def allStatesMatch = true
     issueLinkManager.getOutwardLinks(epic.id).find { link -> 
        def linkedIssue = link.destinationObject
        if(linkedIssue.issueType.name == "Story" ||linkedIssue.issueType.name == "Task"||linkedIssue.issueType.name == "Sub-task") {
            //one mismatch is enough to break
         if(linkedIssue.getStatus().name != stateToCheck) {
             allStatesMatch = false
             return true //break out of the find loop
         }
        } else {
            log.warn("checkEpicsLinkedTicketsForDesiredState_ALL: Found a different linked Ticket type - ignoring logic for Issue Key" + linkedIssue.getKey())
        }
     }
     log.warn("checkEpicsLinkedTicketsForDesiredState_ALL: Return value is " + allStatesMatch)
     return allStatesMatch
 } 
 
 //func: main method functionality for Story issue types
 def executeLinkedTicketLogicForState = {Issue issue ->
        
     log.warn("Executing Story Logic for State " + ISSUE_STATE);
     //get the parent Epic via the Story's epic link
     def Issue epic = getEpicFromSubticketEpicLink(issue)
    
    if(epic!=null) {
    
        log.warn("Correlating STATES...");
        if(ISSUE_STATE == STATE_TODO) {
            log.warn("STEP - " + ISSUE_TYPE + " -- > " + ISSUE_STATE);
            //check ALL Linked Tickets for the same state.  If all are in line, finally transition the Epic to the same state.
            if (checkAllLinkedTickets_STORY_TASK_SUBTASK_ONLY_OnEpicForDesiredState(epic, STATE_TODO)) {
                //transition the Epic
                log.warn("SECOF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> can transition ("+ SECOF_RT_SpecifyWork + ") Epic (" + epic.getKey() + ") to match state as ALL child tickets ARE aligned - proceeding")
                transitionIssueToDesiredState(epic, SECOF_RT_SpecifyWork, true, false) //comment on success only
            } else {
                //dont transition it
                log.warn("SECOF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> will not transition ("+ SECOF_RT_SpecifyWork + ") Epic (" + epic.getKey() + ") to match state at this time - ALL child tickets are NOT aligned.")
            }
 
        }
    
        if(ISSUE_STATE == STATE_IN_PROGRESS) {
            log.warn("STEP - " + ISSUE_TYPE + " -- > " + ISSUE_STATE);
            //check the Epic and transition to STATE_IN_PROGRESS if not already there.
            if(isIssueNotInState(epic, STATE_IN_PROGRESS)) {
                //transition the Epic
                log.warn("SECOFF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> can transition  ("+ SECOF_RT_StartWork + ") Epic (" + epic.getKey() + ") to match state as ALL child tickets ARE aligned - proceeding")                       
                transitionIssueToDesiredState(epic, SECOF_RT_StartWork, true, false) //comment on success only
            } else {
                //dont transition it
                log.warn("SECOFF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> will not transition  ("+ SECOF_RT_StartWork + ") Epic (" + epic.getKey() + ") to match state at this time - ALL child tickets are NOT aligned.")            
            }
        }
    
        if(ISSUE_STATE == STATE_DONE) {
            log.warn("STEP - " + ISSUE_TYPE + " -- > " + ISSUE_STATE);
            //check ALL Linked Tickets for the same state.  If all are in line, finally transition the Epic to the same state.
            if (checkAllLinkedTickets_STORY_TASK_SUBTASK_ONLY_OnEpicForDesiredState(epic, STATE_DONE)) {
                //transition the Epic
                log.warn("SECOF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> can transition ("+ SECOF_RT_FinishWork + ") Epic (" + epic.getKey() + ") to match state as ALL child tickets ARE aligned - proceeding")                
                transitionIssueToDesiredState(epic, SECOF_RT_FinishWork, true, false) //comment on success only)
            } else {
                //dont transition it
                log.warn("SECOF AUTOMATION - UC:" + issue.getKey() + " / " + ISSUE_TYPE + " / " + ISSUE_STATE + " / " + " --> will not transition ("+ SECOF_RT_FinishWork + ") Epic (" + epic.getKey() + ") to match state at this time - ALL child tickets are NOT aligned.")
            }        
        }   
    }
 }
 
 //func: main method for executiing logic on Epic type tickets
def executeEpicLogicForState = {Issue issue -> 
     
     log.warn("Executing Epic Logic for State " + ISSUE_STATE);
     
     if(ISSUE_STATE == STATE_TODO) {
         
         log.warn("STEP - " + ISSUE_TYPE + " -- > " + ISSUE_STATE);
         
         //Ensure all Child Tickets are transitioned to this state if not already in that state.
         
         issueLinkManager.getOutwardLinks(issue.id).each { issueLink ->
             
             def linkedIssue = issueLink.destinationObject
             if(isIssueNotInState(linkedIssue, ISSUE_STATE)) {
                 
                 if (linkedIssue.issueType.name == ISSUE_TYPE_STORY) {
                     
                     log.warn("SECOFF AUTOMATION:" + linkedIssue.getKey() + " / " + ISSUE_TYPE_STORY + " / " + ISSUE_STATE + " / " + " --> attempting auto-transition  ("+ SECOF_RT_SpecifyWork + ") by Epic (" + issue.getKey() + ") to match state.")
                     transitionIssueToDesiredState(linkedIssue,SECOF_RT_SpecifyWork, true, true) //comment on both success and failure
                 }
             
                if(linkedIssue.issueType.name == ISSUE_TYPE_TASK||linkedIssue.issueType.name == ISSUE_TYPE_SUBTASK) {
                    
                    log.warn("SECOFF AUTOMATION:" + linkedIssue.getKey() + " / " + linkedIssue.issueType.name + " / " + ISSUE_STATE + " / " + " --> attempting auto-transition  ("+ SECOF_RT_SpecifyWork + ") by Epic (" + issue.getKey() + ") to match state.")
                    transitionIssueToDesiredState(linkedIssue,SECOF_RT_ST_SpecifyWork, true, true) //comment on both success and failure                    
                    
                 }
             }
         }
     }
    
     if(ISSUE_STATE == STATE_DONE) {
         
         log.warn("STEP - " + ISSUE_TYPE + " -- > " + ISSUE_STATE);
         
         //Ensure all Child Tickets are transitioned to this state if not already in that state.
         
         issueLinkManager.getOutwardLinks(issue.id).each { issueLink ->
             
             def linkedIssue = issueLink.destinationObject
             if(isIssueNotInState(linkedIssue, ISSUE_STATE)) {
                 
                 if (linkedIssue.issueType.name == ISSUE_TYPE_STORY) {
                     
                     log.warn("SECOFF AUTOMATION:" + linkedIssue.getKey() + " / " + ISSUE_TYPE_STORY + " / " + ISSUE_STATE + " / " + " --> attempting auto-transition  ("+ SECOF_RT_SpecifyWork + ") by Epic (" + issue.getKey() + ") to match state.")                 
                     transitionIssueToDesiredState(linkedIssue,SECOF_RT_FinishWork, true, true) //comment on both success and failure
                }
             
                if(linkedIssue.issueType.name == ISSUE_TYPE_TASK||linkedIssue.issueType.name == ISSUE_TYPE_SUBTASK) {
                    log.warn("SECOFF AUTOMATION:" + linkedIssue.getKey() + " / " + linkedIssue.issueType.name + " / " + ISSUE_STATE + " / " + " --> attempting auto-transition  ("+ SECOF_RT_SpecifyWork + ") by Epic (" + issue.getKey() + ") to match state.")
                    transitionIssueToDesiredState(linkedIssue,SECOF_RT_ST_FinishWork, true, true) //comment on both success and failure                    
                }
             }
        }
     }
 }
         
 
 
 /** Main Method */
 
 authContext.setLoggedInUser(autoUser)
 log.warn("Postfunction called");
 
 /** At runtime we need to check the following:
 * 1) What type of ticket is executing this post function
 * 2) What is the state of that ticket following the transition that precedes this post function
 */
 
 
 
log.warn("SECOF: Executing Switch Statement for IssueType ("+ISSUE_TYPE+") with IssueState ("+ISSUE_STATE+")...")
 
switch (ISSUE_TYPE) {
    
    // 1. We're an Epic.  So we need to check our state and then check all linked Tickets' states, then make a decision on the appropriate transition
    
    case ISSUE_TYPE_EPIC: log.warn("SECOF: Executing Epic - Running logic for Parent Ticket"); executeEpicLogicForState(issue);break;
    
    // 2. We're a Story.  So we need to check the Epic state and then make a decision on the appropriate transition
    
    case ISSUE_TYPE_STORY: log.warn("SECOF: Executing Story -  Running logic for child tickets"); executeLinkedTicketLogicForState(issue);break;
 
    // 3. We're a Task.  So we need to check the Epic state and then make a decision on the appropriate transition
    
    case ISSUE_TYPE_TASK: log.warn("SECOF: Executing Task - Running logic for child tickets"); executeLinkedTicketLogicForState(issue);break;
    
    // 4. We're a Sub-task.  So we need to check the Epic state and then make a decision on the appropriate transition
    
    case ISSUE_TYPE_SUBTASK: log.warn("SECOF: Executing Sub-task -  Running logic for child tickets"); executeLinkedTicketLogicForState(issue);break;
}
