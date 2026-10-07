import json
from pathlib import Path
s=json.loads(Path('contracts/openapi.yaml').read_text())['components']['schemas']
p=Path('apps/api/src/main/java/com/signalframe/contract');p.mkdir(parents=True,exist_ok=True)
def typ(v):
 if '$ref' in v:return v['$ref'].split('/')[-1]
 if v.get('type')=='array':return 'java.util.List<@jakarta.validation.Valid '+typ(v['items'])+'>'
 if v.get('type') not in {'string','integer','number','boolean'}:raise ValueError(f'Unsupported schema: {v}')
 return {'string':'UUID' if v.get('format')=='uuid' else 'Instant' if v.get('format')=='date-time' else 'String','integer':'Long' if v.get('format')=='int64' else 'Integer','number':'Double','boolean':'Boolean'}.get(v.get('type'),'Object')
for n,v in s.items():
 if 'enum' in v:body='public enum '+n+' { '+', '.join(v['enum'])+' }'
 elif v.get('type')=='object':
  fields=[]
  for k,f in v['properties'].items():
   a=['@jakarta.validation.Valid'] if f.get('type')!='array' else []
   if k in v.get('required',[]) and not f.get('nullable'):a+=['@jakarta.validation.constraints.NotNull']
   if 'minimum' in f:a+=[f'@jakarta.validation.constraints.Min({f["minimum"]})'] if f['type']=='integer' else [f'@jakarta.validation.constraints.DecimalMin("{f["minimum"]}")']
   if 'maximum' in f:a+=[f'@jakarta.validation.constraints.Max({f["maximum"]})'] if f['type']=='integer' else [f'@jakarta.validation.constraints.DecimalMax("{f["maximum"]}")']
   if f.get('minLength',0)>0:a+=['@jakarta.validation.constraints.NotBlank']
   if 'maxLength' in f:a+=[f'@jakarta.validation.constraints.Size(max={f["maxLength"]})']
   if f.get('type')=='string' and 'enum' in f:a+=[f'@jakarta.validation.constraints.Pattern(regexp="{"|".join(f["enum"])}")']
   if f.get('type')=='boolean' and f.get('enum')==[False]:a+=['@jakarta.validation.constraints.AssertFalse']
   if 'pattern' in f:a+=[f'@jakarta.validation.constraints.Pattern(regexp="{f["pattern"]}")']
   fields.append('    '+' '.join(a)+' '+typ(f)+' '+k)
  body='public record '+n+'(\n'+',\n'.join(fields)+'\n) {}'
 else:continue
 (p/(n+'.java')).write_text('// Generated from contracts/openapi.yaml; do not edit.\npackage com.signalframe.contract;\nimport java.util.UUID;\nimport java.time.Instant;\n\n'+body+'\n')

def schema(v):
 if isinstance(v,list):return [schema(x) for x in v]
 if not isinstance(v,dict):return v
 out={k:schema(x) for k,x in v.items() if k!='nullable'}
 if v.get('nullable'):
  if '$ref' in out:return {'anyOf':[out,{'type':'null'}]}
  out['type']=[out['type'],'null']
 return out
result={'$schema':'https://json-schema.org/draft/2020-12/schema','$comment':'Generated from OpenAPI; do not edit.','allOf':[{'$ref':'#/components/schemas/AnalysisResult'}],'components':{'schemas':schema(s)}}
text=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
Path('contracts/analysis-result.schema.json').write_text(text)
Path('apps/api/src/main/resources/analysis-result.schema.json').write_text(text)
