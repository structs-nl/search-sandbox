
const { open, readFile, writeFile } = await import("node:fs/promises");
const { parseXml } = await import("@rgrove/parse-xml");

const invent =  JSON.parse(await readFile('invent-list.json','utf8'))

const proc = (c, parents = []) => {

    const level = c.attributes["level"];
    
    const attrs = c.children.filter( (x => x["name"] == "did"))[0]
          ?.children.filter( (x => x["name"] == "unittitle" || x["name"] == "unitid" ||  x["name"] == "unitdate" ))
	  .map (x => (
	      {  "name": x["name"], 
		 "label": x.children[0].text,
		 "attr": x.attributes
	      }
	  ));
    
    const uuid = attrs?.filter(x => x["name"] == "unitid" && x.attr["type"] == "urn:uuid")?.[0].label;    
    const id = attrs?.filter(x => x["name"] == "unitid" && x.attr["identifier"] !== null)?.[0].label;
    const title = attrs?.filter(x => x["name"] == "unittitle")?.[0].label;

    // TODO: check cases when the normal is not filled 
    const date = attrs?.filter(x => x["name"] == "unitdate")?.[0]?.attr["normal"]
    const doc = {"type": level, "uuid": uuid, "title": title};

    if (date != null)
	doc["date"] = date
    
    if (level == "file" && id != null) {

	doc["id"] = id
	const storeprefix = "https://objectstore.surf.nl/87435b768620494e8e911c83d1997f24:globalise-data/NL-HaNA/1.04.02/"
	doc["texturl"] = storeprefix + id + "/text.json"
    }

    doc["parents"] = parents;
   
    const newparents = parents.slice(0);
    if (uuid)
	newparents.push(uuid);
    
    // the recursive step
    // process the childs with the updated parents list
    
    const childs  = c.children.filter( (x => x["name"] == "c"))
    	  .flatMap((x) => proc(x, newparents));

    // return a flat list of the current doc and it's childs
    
    return [doc].concat(childs)
}

console.log("starting...")

const data = await readFile('../data/1.04.02.xml','utf8');
const ead = parseXml(data);

const dsc = ead.children[0].
      children.filter( (x => x["name"] == "ListRecords"))[0].
      children.filter( (x => x["name"] == "record"))[0].
      children.filter( (x => x["name"] == "metadata"))[0].
      children.filter( (x => x["name"] == "ead"))[0].
      children.filter( (x => x["name"] == "archdesc"))[0].
      children.filter( (x => x["name"] == "dsc"))[0];


const list = proc(dsc).filter(x => invent.includes(x.id))

// TODO remove the irrelevant series

const jsonstr = JSON.stringify(list, null,  "\t");

writeFile("output.json", jsonstr);


