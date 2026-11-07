import {readFile,readdir,access} from 'node:fs/promises';
import path from 'node:path';

// A practice can collect independent observations from multiple applets.
export async function practiceCatalog(root) {
  const base=path.join(root,'content/builder');
  const practices=JSON.parse(await readFile(path.join(base,'practices.json'),'utf8'));
  for (const p of practices) {
    if (!p.id || !Number.isInteger(p.revision) || !p.dimension || !['all','ui','tables','media','capture','async','data','editable','upgrade'].includes(p.applicability) || !p.rationale || !p.acceptance?.length || !p.shared_default || !p.checks?.length || !['runtime','review','validation'].includes(p.enforcement) || typeof p.exceptions_allowed !== 'boolean') throw Error('Incomplete practice contract: '+p.id);
  }
  const byId=new Map(practices.map(p=>[p.id,{...p,evidence:[]}]));
  if(byId.size!==practices.length) throw Error('Duplicate practice ID');
  const dir=path.join(base,'practice-observations');
  for(const file of (await readdir(dir)).filter(f=>f.endsWith('.json')).sort()) {
    const {application,observations}=JSON.parse(await readFile(path.join(dir,file),'utf8'));
    for(const entry of observations) {
      const p=byId.get(entry.practice);
      if(!p || !application || !entry.finding || !['implementation','flow-test','device-test','policy'].includes(entry.basis)) throw Error('Invalid observation in '+file);
      if(!entry.source || entry.source.includes('..') || path.isAbsolute(entry.source)) throw Error('Expected repository evidence in '+file);
      await access(path.join(process.env.BRIDGIT_PUBLIC_DIR||path.join(root,'..'),entry.source));
      p.evidence.push({application,finding:entry.finding,basis:entry.basis,source:entry.source});
    }
  }
  return {format:'bridgit-builder-practices/1',contract:'bridgit-practices/1',version:'1.0.0',practices:[...byId.values()].map(p=>{
    if(!p.evidence.length) throw Error('Missing evidence for '+p.id);
    const applications=[...new Set(p.evidence.map(e=>e.application))].sort();
    return {...p,applications,observed_across_applications:applications.length>1,
      device_verified:p.evidence.some(e=>e.basis==='device-test')};
  }),by_task:Object.fromEntries([...new Set(practices.flatMap(p=>p.tasks))].sort().map(task=>[task,practices.filter(p=>p.tasks.includes(task)).map(p=>p.id)]))};
}
