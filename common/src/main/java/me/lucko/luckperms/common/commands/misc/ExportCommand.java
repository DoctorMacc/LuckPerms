/*
 * Copyright (c) 2016 Lucko (Luck) <luck@lucko.me>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.commands.misc;

import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.common.commands.Arg;
import me.lucko.luckperms.common.commands.CommandResult;
import me.lucko.luckperms.common.commands.SingleCommand;
import me.lucko.luckperms.common.commands.sender.Sender;
import me.lucko.luckperms.common.constants.Message;
import me.lucko.luckperms.common.constants.Permission;
import me.lucko.luckperms.common.core.NodeFactory;
import me.lucko.luckperms.common.core.model.Group;
import me.lucko.luckperms.common.core.model.Track;
import me.lucko.luckperms.common.core.model.User;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.storage.Storage;
import me.lucko.luckperms.common.utils.Predicates;
import me.lucko.luckperms.common.utils.ProgressLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class ExportCommand extends SingleCommand {
    private static void write(BufferedWriter writer, String s) {
        try {
            writer.write(s);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ExportCommand() {
        super("Export", "Export data to a file", "/%s export <file>", Permission.EXPORT, Predicates.not(1),
                Arg.list(
                        Arg.create("file", true, "the file to export to")
                )
        );
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, List<String> args, String label) {
        ProgressLogger log = new ProgressLogger(null, Message.EXPORT_LOG, Message.EXPORT_LOG_PROGRESS);
        log.addListener(plugin.getConsoleSender());
        log.addListener(sender);

        File f = new File(plugin.getDataDirectory(), args.get(0));
        if (f.exists()) {
            Message.LOG_EXPORT_ALREADY_EXISTS.send(sender, f.getAbsolutePath());
            return CommandResult.INVALID_ARGS;
        }

        try {
            f.createNewFile();
        } catch (IOException e) {
            Message.LOG_EXPORT_FAILURE.send(sender);
            e.printStackTrace();
            return CommandResult.FAILURE;
        }

        if (!Files.isWritable(f.toPath())) {
            Message.LOG_EXPORT_NOT_WRITABLE.send(sender, f.getAbsolutePath());
            return CommandResult.FAILURE;
        }

        try (FileWriter fWriter = new FileWriter(f, true); BufferedWriter writer = new BufferedWriter(fWriter)) {
            log.log("Starting.");

            // Export Groups
            log.log("Starting group export.");

            // Create the actual groups first
            for (Group group : plugin.getGroupManager().getAll().values()) {
                write(writer, "/luckperms creategroup " + group.getName());
            }

            AtomicInteger groupCount = new AtomicInteger(0);
            for (Group group : plugin.getGroupManager().getAll().values()) {
                for (Node node : group.getNodes()) {
                    write(writer, NodeFactory.nodeAsCommand(node, group.getName(), true));
                }
                log.logAllProgress("Exported {} groups so far.", groupCount.incrementAndGet());
            }
            log.log("Exported " + groupCount.get() + " groups.");

            // Export tracks
            log.log("Starting track export.");

            // Create the actual tracks first
            for (Track track : plugin.getTrackManager().getAll().values()) {
                write(writer, "/luckperms createtrack " + track.getName());
            }

            AtomicInteger trackCount = new AtomicInteger(0);
            for (Track track : plugin.getTrackManager().getAll().values()) {
                for (String group : track.getGroups()) {
                    write(writer, "/luckperms track " + track.getName() + " append " + group);
                }
                log.logAllProgress("Exported {} tracks so far.", trackCount.incrementAndGet());
            }
            log.log("Exported " + trackCount.get() + " tracks.");

            // Export users
            log.log("Starting user export. Finding a list of unique users to export.");
            Storage ds = plugin.getStorage();
            Set<UUID> users = ds.getUniqueUsers().join();
            log.log("Found " + users.size() + " unique users to export.");

            AtomicInteger userCount = new AtomicInteger(0);
            for (UUID uuid : users) {
                plugin.getStorage().loadUser(uuid, "null").join();
                User user = plugin.getUserManager().get(uuid);

                boolean inDefault = false;
                for (Node node : user.getNodes()) {
                    if (node.isGroupNode() && node.getGroupName().equalsIgnoreCase("default")) {
                        inDefault = true;
                        continue;
                    }

                    write(writer, NodeFactory.nodeAsCommand(node, user.getUuid().toString(), false));
                }

                if (!user.getPrimaryGroup().equalsIgnoreCase("default")) {
                    write(writer, "/luckperms user " + user.getUuid().toString() + " switchprimarygroup " + user.getPrimaryGroup());
                }

                if (!inDefault) {
                    write(writer, "/luckperms user " + user.getUuid().toString() + " parent remove default");
                }

                plugin.getUserManager().cleanup(user);
                log.logProgress("Exported {} users so far.", userCount.incrementAndGet());
            }
            log.log("Exported " + userCount.get() + " users.");

            try {
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            log.getListeners().forEach(l -> Message.LOG_EXPORT_SUCCESS.send(l, f.getAbsolutePath()));
            return CommandResult.SUCCESS;
        } catch (Throwable t) {
            t.printStackTrace();
            return CommandResult.FAILURE;
        }
    }

}
