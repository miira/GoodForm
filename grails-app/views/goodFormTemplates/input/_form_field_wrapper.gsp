<div class="formField ${error ? 'has-error' : ''}" title="${error}">

  <g:if test="${preamble}"><div class='preamble'>${preamble.encodeAsHTML()}</div></g:if>

  <label for="${name}" class="${labelClass}">${label.encodeAsHTML()}</label>

  <g:if test="${prefix}"><span class='prefix'>${prefix}</span></g:if>
  <g:render template="/goodFormTemplates/input/type_${type.toLowerCase()}"/>
  <g:if test="${units}"><span class='units'>${units}</span></g:if>
  <g:if test="${required}"><span class='required'>${required ? '*' : ''}</span></g:if>
  <g:if test="${error}"><span class="text text-danger"><span class="fa fa-warning"></span>&nbsp;${error}</span></g:if>
  <g:if test="${hint}"><p class='hint help-block'>${hint}</p></g:if>
</div>