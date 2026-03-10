package cn.earthsky.dev.project.lapluma.common.text;

import cn.earthsky.dev.project.lapluma.LaPluma;
import com.google.common.base.Charsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class ConversationLoader {

    public static ConversationStructure loadStructureFromString(String name, String content) {
        List<String> allLines = Arrays.asList(content.split("\n"));
        ConversationStructure structure = new ConversationStructure(name);
        structure.loadPrompts(allLines);
        LaPluma.getLogger().log(Level.INFO, "[ConversationLoader] Loaded from string: " + name + " (" + allLines.size() + " lines)");
        return structure;
    }

    public static ConversationStructure loadStructureFromResource(String name){
        IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        IResource resource;
        try{
            resource = rm.getResource(new ResourceLocation("lapluma" ,"journals/" + name + ".journal"));
        }catch (Throwable throwable){
            LaPluma.getLogger().log(Level.WARNING,"cannot load " + name +  ".journal", throwable);
            return null;
        }


        try {
            List<String> allLines = new ArrayList<>();
            Reader stream = new InputStreamReader(resource.getInputStream(), Charsets.UTF_8.newDecoder());
            BufferedReader reader = new BufferedReader(stream);
            for (;;) {
                String line = reader.readLine();
                if (line == null)
                    break;
                allLines.add(line);
            }
            ConversationStructure conversationStructure = new ConversationStructure(name);
            conversationStructure.loadPrompts(allLines);
            return conversationStructure;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
